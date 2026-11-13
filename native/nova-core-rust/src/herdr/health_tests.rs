// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
use super::*;

fn pong(client: &mut Client, nonce: &str) -> Output {
    let mut bytes = Vec::new();
    protocol::write_message(
        &mut bytes,
        &ServerMessage::EndpointControl {
            kind: HEALTH_PONG_KIND.into(),
            data: nonce.into(),
        },
    )
    .unwrap();
    client.receive(&bytes).unwrap()
}

#[test]
fn only_the_pending_endpoint_nonce_proves_health() {
    let mut client = navigation::tests::client();
    client
        .welcome
        .as_mut()
        .unwrap()
        .capabilities
        .push(HEALTH_CHECK_CAPABILITY.into());
    let output = client.probe("first".into()).unwrap();
    let message: ClientMessage =
        protocol::read_message(&mut &output.outbound[..], MAX_FRAME_SIZE).unwrap();
    assert!(
        matches!(message, ClientMessage::EndpointControl { kind, data }
        if kind == HEALTH_PING_KIND && data == "first")
    );
    assert!(pong(&mut client, "unrequested").health_result.is_none());
    client.probe("replacement".into()).unwrap();
    assert!(pong(&mut client, "first").health_result.is_none());
    assert_eq!(
        pong(&mut client, "replacement").health_result,
        Some(("replacement".into(), true))
    );
    assert!(pong(&mut client, "replacement").health_result.is_none());
}

#[test]
fn unsupported_health_requests_fail_without_sending_unknown_messages() {
    let mut client = navigation::tests::client();
    let output = client.probe("probe".into()).unwrap();
    assert!(output.outbound.is_empty());
    assert_eq!(output.health_result, Some(("probe".into(), false)));
    assert!(pong(&mut client, "probe").health_result.is_none());
    assert!(client.probe(String::new()).is_err());
    assert!(client.probe("x".repeat(129)).is_err());
}
