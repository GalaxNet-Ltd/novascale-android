// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
//
// SPDX-License-Identifier: Apache-2.0

use crate::model::{Command, CoreEvent, StartRequest};
use crate::session::{EventSender, SessionError, connect_authenticated, emit};
use base64::Engine;
use russh::Disconnect;
use russh_sftp::client::{SftpSession, error::Error as SftpError};
use russh_sftp::protocol::{FileType, OpenFlags, StatusCode};
use serde_json::Value;
use serde_json::json;
use std::future::Future;
use std::os::fd::OwnedFd;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::mpsc;
use tokio::task::JoinSet;
use tokio_util::sync::CancellationToken;

const MAX_EDIT_BYTES: usize = 2 * 1024 * 1024;

pub async fn run_sftp(
    fd: OwnedFd,
    mut request: StartRequest,
    events: EventSender,
    mut commands: mpsc::Receiver<Command>,
    cancel: CancellationToken,
) -> Result<(), SessionError> {
    let session = connect_authenticated(fd, &mut request, &events).await?;
    let channel = session
        .channel_open_session()
        .await
        .map_err(|_| SessionError::Channel)?;
    channel
        .request_subsystem(true, "sftp")
        .await
        .map_err(|_| SessionError::Channel)?;
    let sftp = Arc::new(
        SftpSession::new(channel.into_stream())
            .await
            .map_err(|_| SessionError::Channel)?,
    );
    sftp.set_timeout(15);
    emit(&events, CoreEvent::named("sftp_ready"));

    loop {
        tokio::select! {
            _ = cancel.cancelled() => break,
            Some(command) = commands.recv() => match command {
                Command::SftpList { request_id, path } => {
                    let response = tokio::select! {
                        _ = cancel.cancelled() => break,
                        response = tokio::time::timeout(Duration::from_secs(30), list_directory(&sftp, &path)) =>
                            response.unwrap_or_else(|_| json!({"error": "Remote directory lookup timed out"})),
                    };
                    emit_payload(&events, "sftp_list", &request_id, &response);
                }
                Command::SftpRead { request_id, path } => {
                    let response = read_file(&sftp, &path).await;
                    emit_payload(&events, "sftp_read", &request_id, &response);
                }
                Command::SftpWrite { request_id, path, base64 } => {
                    let response = match base64::engine::general_purpose::STANDARD.decode(base64) {
                        Ok(data) if data.len() <= MAX_EDIT_BYTES =>
                            write_atomic(&sftp, &path, &request_id, &data).await,
                        Ok(_) => json!({"error": "The edited file is too large"}),
                        Err(_) => json!({"error": "The edited file payload is invalid"}),
                    };
                    emit_payload(&events, "sftp_write", &request_id, &response);
                }
                Command::SftpCreateDirectory { request_id, path } => {
                    let response = if path_occupied(&sftp, &path).await {
                        json!({"error": "A remote item with this name already exists"})
                    } else {
                        match sftp.create_dir(path.clone()).await {
                            Ok(()) => json!({"path": path, "created": true}),
                            Err(_) => json!({"error": "Unable to create the remote folder"}),
                        }
                    };
                    emit_payload(&events, "sftp_create", &request_id, &response);
                }
                Command::SftpCreateFile { request_id, path } => {
                    let response = if path_occupied(&sftp, &path).await {
                        json!({"error": "A remote item with this name already exists"})
                    } else {
                        match sftp.open_with_flags(path.clone(), OpenFlags::WRITE | OpenFlags::CREATE | OpenFlags::EXCLUDE).await {
                            Ok(mut file) => match file.shutdown().await {
                                Ok(()) => json!({"path": path, "created": true}),
                                Err(_) => json!({"error": "Unable to finish creating the remote file"}),
                            },
                            Err(_) => json!({"error": "Unable to create the remote file"}),
                        }
                    };
                    emit_payload(&events, "sftp_create", &request_id, &response);
                }
                Command::SftpRename {
                    request_id,
                    source_path,
                    destination_path,
                } => {
                    let response = if path_occupied(&sftp, &destination_path).await {
                        json!({"error": "A remote item with this name already exists"})
                    } else {
                        match sftp.rename(source_path.clone(), destination_path.clone()).await {
                            Ok(()) => json!({
                                "sourcePath": source_path,
                                "destinationPath": destination_path,
                                "renamed": true,
                            }),
                            Err(_) => json!({"error": "Unable to rename the remote item"}),
                        }
                    };
                    emit_payload(&events, "sftp_rename", &request_id, &response);
                }
                Command::SftpDelete {
                    request_id,
                    path,
                    directory,
                } => {
                    let result = if directory {
                        sftp.remove_dir(path.clone()).await
                    } else {
                        sftp.remove_file(path.clone()).await
                    };
                    let response = match result {
                        Ok(()) => json!({"path": path, "deleted": true}),
                        Err(_) if directory => json!({"error": "Unable to delete the remote folder. Make sure it is empty."}),
                        Err(_) => json!({"error": "Unable to delete the remote file"}),
                    };
                    emit_payload(&events, "sftp_delete", &request_id, &response);
                }
                Command::SftpUpload {
                    request_id,
                    path,
                    local_fd,
                    total_bytes,
                } => {
                    let transfer = upload_from_fd(
                        &sftp,
                        &events,
                        &request_id,
                        &path,
                        local_fd.into_owned(),
                        total_bytes,
                    );
                    match wait_for_transfer(transfer, &request_id, &mut commands, &cancel).await {
                        TransferWait::Completed(response) => {
                            emit_payload(&events, "sftp_upload", &request_id, &response);
                        }
                        TransferWait::Canceled => {
                            let _ = sftp
                                .remove_file(temporary_sibling(&path, &request_id, "upload"))
                                .await;
                            emit_payload(
                                &events,
                                "sftp_upload",
                                &request_id,
                                &json!({"path": path, "canceled": true}),
                            );
                        }
                        TransferWait::Close => {
                            let _ = sftp
                                .remove_file(temporary_sibling(&path, &request_id, "upload"))
                                .await;
                            break;
                        }
                    }
                }
                Command::SftpDownload {
                    request_id,
                    path,
                    local_fd,
                } => {
                    let transfer = download_to_fd(
                        &sftp,
                        &events,
                        &request_id,
                        &path,
                        local_fd.into_owned(),
                    );
                    match wait_for_transfer(transfer, &request_id, &mut commands, &cancel).await {
                        TransferWait::Completed(response) => {
                            emit_payload(&events, "sftp_download", &request_id, &response);
                        }
                        TransferWait::Canceled => {
                            emit_payload(
                                &events,
                                "sftp_download",
                                &request_id,
                                &json!({"path": path, "canceled": true}),
                            );
                        }
                        TransferWait::Close => break,
                    }
                }
                Command::SftpCancelTransfer { .. } => {}
                Command::Close => break,
                _ => {}
            }
        }
    }

    let _ = sftp.close().await;
    let _ = session
        .disconnect(Disconnect::ByApplication, "", "en")
        .await;
    emit(&events, CoreEvent::named("closed"));
    Ok(())
}

enum TransferWait {
    Completed(Value),
    Canceled,
    Close,
}

async fn wait_for_transfer<F>(
    transfer: F,
    request_id: &str,
    commands: &mut mpsc::Receiver<Command>,
    cancel: &CancellationToken,
) -> TransferWait
where
    F: Future<Output = Value>,
{
    tokio::pin!(transfer);
    loop {
        tokio::select! {
            response = &mut transfer => return TransferWait::Completed(response),
            _ = cancel.cancelled() => return TransferWait::Close,
            command = commands.recv() => match command {
                Some(Command::SftpCancelTransfer { request_id: canceled_id })
                    if canceled_id == request_id => return TransferWait::Canceled,
                Some(Command::Close) | None => return TransferWait::Close,
                _ => {}
            }
        }
    }
}

// Only links incur an extra STAT; bounded parallelism avoids one network round
// trip per link in directories such as /usr/bin. Keep each link's own path/kind
// for rename/delete and use target metadata only for browsing and file actions.
async fn list_directory(sftp: &Arc<SftpSession>, path: &str) -> Value {
    let entries = match sftp.read_dir(path).await {
        Ok(entries) => entries,
        Err(_) => return json!({"error": "Unable to list the remote directory"}),
    };
    let mut rows = Vec::new();
    let mut pending = JoinSet::new();
    for entry in entries {
        let metadata = entry.metadata();
        let index = rows.len();
        rows.push(json!({
            "name": entry.file_name(), "path": entry.path(),
            "kind": file_type_name(entry.file_type()),
            "size": metadata.size, "modifiedAt": metadata.mtime,
            "permissions": metadata.permissions,
        }));
        if entry.file_type().is_symlink() {
            // A link's byte length is not its target's file size.
            rows[index]["size"] = Value::Null;
            rows[index]["targetKind"] = Value::Null;
            let sftp = Arc::clone(sftp);
            pending.spawn(async move { (index, sftp.metadata(entry.path()).await.ok()) });
            if pending.len() >= 8 {
                apply_link_metadata(&mut rows, pending.join_next().await);
            }
        }
    }
    while !pending.is_empty() {
        apply_link_metadata(&mut rows, pending.join_next().await);
    }
    json!({"path": path, "entries": rows})
}

fn apply_link_metadata(
    rows: &mut [Value],
    result: Option<
        Result<(usize, Option<russh_sftp::protocol::FileAttributes>), tokio::task::JoinError>,
    >,
) {
    if let Some(Ok((index, Some(metadata)))) = result {
        rows[index]["targetKind"] = json!(file_type_name(metadata.file_type()));
        rows[index]["size"] = json!(metadata.size);
        rows[index]["modifiedAt"] = json!(metadata.mtime);
        rows[index]["permissions"] = json!(metadata.permissions);
    }
}

// Unlike STAT/try_exists, LSTAT sees dangling links as occupied names.
async fn path_occupied(sftp: &SftpSession, path: &str) -> bool {
    !matches!(sftp.symlink_metadata(path).await,
        Err(SftpError::Status(status)) if status.status_code == StatusCode::NoSuchFile)
}

async fn read_file(sftp: &SftpSession, path: &str) -> Value {
    let result = async {
        let mut file = sftp.open(path).await.map_err(|_| ())?;
        let mut data = Vec::new();
        (&mut file)
            .take((MAX_EDIT_BYTES + 1) as u64)
            .read_to_end(&mut data)
            .await
            .map_err(|_| ())?;
        file.shutdown().await.map_err(|_| ())?;
        Ok::<_, ()>(data)
    }
    .await;
    match result {
        Ok(data) if data.len() <= MAX_EDIT_BYTES => json!({
            "path": path, "base64": base64::engine::general_purpose::STANDARD.encode(data),
        }),
        Ok(_) => json!({"error": "The remote file is too large to edit"}),
        Err(()) => json!({"error": "Unable to read the remote file"}),
    }
}

async fn write_atomic(sftp: &SftpSession, path: &str, request_id: &str, data: &[u8]) -> Value {
    // Replacing the link pathname would silently turn it into a regular file.
    // Resolve a final-component link on the server, including relative/chained
    // targets, and stage/rename beside that target. Never fall back to replacing
    // the link when REALPATH fails or a server leaves it unresolved.
    let destination = match sftp.symlink_metadata(path).await {
        Ok(metadata) if metadata.file_type().is_symlink() => match sftp.canonicalize(path).await {
            Ok(target) => match sftp.symlink_metadata(target.clone()).await {
                Ok(target_metadata) if target_metadata.file_type().is_file() => target,
                _ => {
                    return json!({"error": "Unable to resolve the symbolic link target for saving"});
                }
            },
            Err(_) => {
                return json!({"error": "Unable to resolve the symbolic link target for saving"});
            }
        },
        Ok(metadata) if metadata.file_type().is_file() => path.to_owned(),
        _ => return json!({"error": "The remote file is no longer available for saving"}),
    };
    let saved_path = path;
    let path = destination.as_str();
    let temporary_path = temporary_sibling(path, request_id, "edit");
    let backup_path = temporary_sibling(path, request_id, "backup");
    let result = async {
        let mut file = sftp.create(temporary_path.clone()).await.map_err(|_| ())?;
        file.write_all(data).await.map_err(|_| ())?;
        file.shutdown().await.map_err(|_| ())?;

        // Servers with overwrite-capable rename provide a truly atomic
        // replacement. SFTP v3 servers that reject an existing destination
        // use a recoverable backup swap so an edit still never truncates the
        // original in place.
        if sftp
            .rename(temporary_path.clone(), path.to_owned())
            .await
            .is_ok()
        {
            return Ok::<bool, ()>(true);
        }
        sftp.rename(path.to_owned(), backup_path.clone())
            .await
            .map_err(|_| ())?;
        if sftp
            .rename(temporary_path.clone(), path.to_owned())
            .await
            .is_err()
        {
            let _ = sftp.rename(backup_path.clone(), path.to_owned()).await;
            return Err(());
        }
        let _ = sftp.remove_file(backup_path.clone()).await;
        Ok(false)
    }
    .await;
    match result {
        Ok(atomic) => json!({"path": saved_path, "saved": true, "atomic": atomic}),
        Err(()) => {
            let _ = sftp.remove_file(temporary_path).await;
            if !sftp.try_exists(path.to_owned()).await.unwrap_or(false) {
                let _ = sftp.rename(backup_path, path.to_owned()).await;
            }
            json!({"error": "Unable to save the remote file atomically"})
        }
    }
}

async fn upload_from_fd(
    sftp: &SftpSession,
    events: &EventSender,
    request_id: &str,
    path: &str,
    local_fd: OwnedFd,
    total_bytes: Option<u64>,
) -> Value {
    let mut local = tokio::fs::File::from_std(std::fs::File::from(local_fd));
    let temporary_path = temporary_sibling(path, request_id, "upload");
    if path_occupied(sftp, path).await {
        return json!({"error": "A remote item with this name already exists"});
    }
    let result = async {
        let mut remote = sftp.create(temporary_path.clone()).await.map_err(|_| ())?;
        let copied = copy_with_progress(
            &mut local,
            &mut remote,
            events,
            "upload",
            request_id,
            total_bytes,
        )
        .await
        .map_err(|_| ())?;
        remote.shutdown().await.map_err(|_| ())?;
        sftp.rename(temporary_path.clone(), path.to_owned())
            .await
            .map_err(|_| ())?;
        Ok::<u64, ()>(copied)
    }
    .await;
    match result {
        Ok(bytes) => json!({"path": path, "bytes": bytes, "complete": true}),
        Err(()) => {
            let _ = sftp.remove_file(temporary_path).await;
            json!({"error": "Unable to upload the selected file"})
        }
    }
}

async fn download_to_fd(
    sftp: &SftpSession,
    events: &EventSender,
    request_id: &str,
    path: &str,
    local_fd: OwnedFd,
) -> Value {
    let mut local = tokio::fs::File::from_std(std::fs::File::from(local_fd));
    let result = async {
        let mut remote = sftp.open(path.to_owned()).await.map_err(|_| ())?;
        let total = remote
            .metadata()
            .await
            .ok()
            .and_then(|metadata| metadata.size);
        let copied = copy_with_progress(
            &mut remote,
            &mut local,
            events,
            "download",
            request_id,
            total,
        )
        .await
        .map_err(|_| ())?;
        local.flush().await.map_err(|_| ())?;
        Ok::<u64, ()>(copied)
    }
    .await;
    match result {
        Ok(bytes) => json!({"path": path, "bytes": bytes, "complete": true}),
        Err(()) => json!({"error": "Unable to download the remote file"}),
    }
}

async fn copy_with_progress<R, W>(
    reader: &mut R,
    writer: &mut W,
    events: &EventSender,
    direction: &str,
    request_id: &str,
    total_bytes: Option<u64>,
) -> std::io::Result<u64>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let mut buffer = vec![0_u8; 128 * 1024];
    let mut transferred = 0_u64;
    loop {
        let count = reader.read(&mut buffer).await?;
        if count == 0 {
            break;
        }
        writer.write_all(&buffer[..count]).await?;
        transferred += count as u64;
        emit_payload(
            events,
            "sftp_transfer_progress",
            request_id,
            &json!({
                "direction": direction,
                "bytes": transferred,
                "totalBytes": total_bytes,
            }),
        );
    }
    writer.flush().await?;
    Ok(transferred)
}

fn temporary_sibling(path: &str, request_id: &str, operation: &str) -> String {
    let (parent, name) = path.rsplit_once('/').unwrap_or(("", path));
    let short_request = request_id
        .chars()
        .filter(|character| character.is_ascii_alphanumeric())
        .take(12)
        .collect::<String>();
    let parent = if parent.is_empty() { "/" } else { parent };
    format!(
        "{}/.{}.novascale-{}-{}",
        parent.trim_end_matches('/'),
        name,
        operation,
        short_request
    )
}

fn emit_payload(
    events: &EventSender,
    event_type: &str,
    request_id: &str,
    payload: &serde_json::Value,
) {
    let mut event = CoreEvent::named(event_type);
    event.request_id = Some(request_id);
    event.payload = Some(payload);
    emit(events, event);
}

fn file_type_name(file_type: FileType) -> &'static str {
    match file_type {
        FileType::Dir => "directory",
        FileType::File => "file",
        FileType::Symlink => "symlink",
        FileType::Other => "other",
    }
}
