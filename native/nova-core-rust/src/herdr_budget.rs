// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0

//! Provisional admission for the hidden Herdr feasibility prototype.
//!
//! This reserves logical capacity before an attachment and its terminal are
//! created. It is not an allocator or an RSS bound. The estimates and global
//! limit require device profiling and product review before exposing Herdr.
use std::sync::{
    Arc, Condvar, Mutex, OnceLock,
    atomic::{AtomicBool, Ordering},
};
use std::time::{Duration, Instant};

// Includes two simultaneously live 16 MiB ANSI buffers while coalescing
// frames, in addition to the provisional retained/decoder budget.
pub(crate) const PROTOCOL_WORKING_SET: usize = 152 * 1024 * 1024;
const GHOSTTY_CEILING: usize = 64 * 1024 * 1024;
const GHOSTTY_AUX_CEILING: usize = 64 * 1024 * 1024;
const BOUNDARY_ALLOWANCE: usize = 64 * 1024 * 1024;
const ATTACHMENT_ALLOWANCE: usize =
    PROTOCOL_WORKING_SET + GHOSTTY_CEILING + GHOSTTY_AUX_CEILING + BOUNDARY_ALLOWANCE;
const GLOBAL_ALLOWANCE: usize = 2 * ATTACHMENT_ALLOWANCE;

struct Pool {
    state: Mutex<PoolState>,
    changed: Condvar,
    limit: usize,
}

#[derive(Default)]
struct PoolState {
    used: usize,
    closing: usize,
}

impl Pool {
    fn new(limit: usize) -> Arc<Self> {
        Arc::new(Self {
            state: Mutex::new(PoolState::default()),
            changed: Condvar::new(),
            limit,
        })
    }

    #[cfg(test)]
    fn try_acquire(self: &Arc<Self>, bytes: usize) -> Option<Arc<Permit>> {
        self.acquire(bytes, Duration::ZERO)
    }

    fn acquire(self: &Arc<Self>, bytes: usize, closing_timeout: Duration) -> Option<Arc<Permit>> {
        if bytes == 0 || bytes > self.limit {
            return None;
        }
        let deadline = Instant::now() + closing_timeout;
        let mut state = self.state.lock().ok()?;
        loop {
            if let Some(next) = state
                .used
                .checked_add(bytes)
                .filter(|next| *next <= self.limit)
            {
                state.used = next;
                return Some(Arc::new(Permit {
                    pool: self.clone(),
                    bytes,
                    closing: AtomicBool::new(false),
                }));
            }
            // Active sessions still enforce the feasibility limit. Only a
            // session explicitly closing can make a replacement wait. The JNI
            // start runs on Kotlin's IO dispatcher, never the Android UI thread.
            if state.closing == 0 {
                return None;
            }
            let remaining = deadline.checked_duration_since(Instant::now())?;
            let (next, _) = self.changed.wait_timeout(state, remaining).ok()?;
            state = next;
        }
    }
}

/// Shared by the session entry and native task; capacity is returned only
/// after all owners release it, including cancellation and close races.
pub(crate) struct Permit {
    pool: Arc<Pool>,
    bytes: usize,
    closing: AtomicBool,
}

impl Permit {
    pub(crate) fn mark_closing(&self) {
        let mut state = self.pool.state.lock().expect("Herdr admission poisoned");
        if !self.closing.swap(true, Ordering::AcqRel) {
            state.closing += self.bytes;
        }
    }
}

impl Drop for Permit {
    fn drop(&mut self) {
        let mut state = self.pool.state.lock().expect("Herdr admission poisoned");
        state.used -= self.bytes;
        if self.closing.load(Ordering::Acquire) {
            state.closing -= self.bytes;
        }
        self.pool.changed.notify_all();
    }
}

pub(crate) fn admit() -> Option<Arc<Permit>> {
    static POOL: OnceLock<Arc<Pool>> = OnceLock::new();
    POOL.get_or_init(|| Pool::new(GLOBAL_ALLOWANCE))
        .acquire(ATTACHMENT_ALLOWANCE, Duration::from_secs(5))
}

#[cfg(test)]
pub(crate) fn isolated_test_permit() -> Arc<Permit> {
    Pool::new(ATTACHMENT_ALLOWANCE)
        .try_acquire(ATTACHMENT_ALLOWANCE)
        .expect("isolated test capacity")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Barrier, atomic::AtomicUsize};

    #[test]
    fn reservation_is_held_by_every_owner_and_released_once() {
        let pool = Pool::new(100);
        let session = pool.try_acquire(60).unwrap();
        let task = session.clone();
        assert!(pool.try_acquire(41).is_none());
        drop(session);
        assert_eq!(pool.state.lock().unwrap().used, 60);
        drop(task);
        assert_eq!(pool.state.lock().unwrap().used, 0);
        assert!(pool.try_acquire(100).is_some());
        assert!(pool.try_acquire(0).is_none());
        assert!(pool.try_acquire(usize::MAX).is_none());
    }

    #[test]
    fn concurrent_admission_never_exceeds_global_capacity() {
        let pool = Pool::new(20);
        let barrier = Arc::new(Barrier::new(33));
        let count = Arc::new(AtomicUsize::new(0));
        let mut workers = Vec::new();
        for _ in 0..32 {
            let (pool, barrier, count) = (pool.clone(), barrier.clone(), count.clone());
            workers.push(std::thread::spawn(move || {
                barrier.wait();
                let permit = pool.try_acquire(10);
                if permit.is_some() {
                    count.fetch_add(1, Ordering::AcqRel);
                }
                barrier.wait();
                drop(permit);
            }));
        }
        barrier.wait();
        barrier.wait();
        for worker in workers {
            worker.join().unwrap();
        }
        assert_eq!(count.load(Ordering::Acquire), 2);
        assert_eq!(pool.state.lock().unwrap().used, 0);
    }

    #[test]
    fn closing_waits_for_all_owners_without_raising_capacity() {
        let pool = Pool::new(20);
        let active = pool.try_acquire(10).unwrap();
        let closing = pool.try_acquire(10).unwrap();
        closing.mark_closing();
        closing.mark_closing(); // Repeated close is idempotent.
        let in_flight = closing.clone();
        drop(closing);
        assert!(pool.acquire(10, Duration::from_millis(10)).is_none());
        assert_eq!(pool.state.lock().unwrap().used, 20);
        assert_eq!(pool.state.lock().unwrap().closing, 10);
        let release = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(30));
            drop(in_flight);
        });
        let replacement = pool.acquire(10, Duration::from_secs(1)).unwrap();
        release.join().unwrap();
        assert_eq!(pool.state.lock().unwrap().closing, 0);
        assert!(
            pool.acquire(10, Duration::from_secs(1)).is_none(),
            "two live sessions still fill the pool"
        );
        drop(active);
        drop(replacement);
        assert_eq!(pool.state.lock().unwrap().used, 0);
    }
}
