use std::{
    io::{Read, Write},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
};
pub const MAX_AUDIO: u64 = 100 * 1024 * 1024;
#[derive(Clone)]
pub struct Ticket {
    pub id: u64,
    pub cancelled: Arc<AtomicBool>,
}
#[derive(Default)]
pub struct Jobs {
    next: u64,
    active: Option<Ticket>,
}
impl Jobs {
    pub fn begin(&mut self) -> Option<Ticket> {
        if self.active.is_some() {
            return None;
        }
        self.next += 1;
        let t = Ticket {
            id: self.next,
            cancelled: Arc::new(AtomicBool::new(false)),
        };
        self.active = Some(t.clone());
        Some(t)
    }
    pub fn matches(&self, id: u64) -> bool {
        self.active.as_ref().is_some_and(|t| t.id == id)
    }
    pub fn busy(&self) -> bool {
        self.active.is_some()
    }
    pub fn cancel(&self) {
        if let Some(t) = &self.active {
            t.cancelled.store(true, Ordering::Relaxed);
        }
    }
    pub fn finish(&mut self, id: u64) -> bool {
        if self.active.as_ref().map(|t| t.id) != Some(id) {
            return false;
        }
        let t = self.active.take().unwrap();
        !t.cancelled.load(Ordering::Relaxed)
    }
}
pub fn copy_audio(
    mut input: impl Read,
    mut out: impl Write,
    expected: Option<u64>,
    cancel: &AtomicBool,
    mut progress: impl FnMut(u64),
) -> Result<u64, String> {
    if expected.is_some_and(|n| n == 0 || n > MAX_AUDIO) {
        return Err("Недопустимый размер аудио".into());
    }
    let mut buffer = [0u8; 32768];
    let mut count = 0u64;
    loop {
        if cancel.load(Ordering::Relaxed) {
            return Err("Загрузка отменена".into());
        }
        let n = input
            .read(&mut buffer)
            .map_err(|_| "Аудиопоток прерван. Повторите попытку.")?;
        if n == 0 {
            break;
        }
        count += n as u64;
        if count > MAX_AUDIO || expected.is_some_and(|e| count > e) {
            return Err("Размер аудио превышен".into());
        }
        out.write_all(&buffer[..n])
            .map_err(|_| "Не удалось сохранить аудио. Проверьте место на диске.")?;
        progress(count);
    }
    if count == 0 || expected.is_some_and(|n| n != count) {
        return Err("Аудио загружено не полностью".into());
    }
    if cancel.load(Ordering::Relaxed) {
        return Err("Загрузка отменена".into());
    }
    out.flush().map_err(|_| "Не удалось сохранить аудио")?;
    Ok(count)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stale_results_and_cancel_cannot_replace_another_request() {
        let mut j = Jobs::default();
        let a = j.begin().unwrap();
        assert!(j.begin().is_none());
        j.cancel();
        assert!(!j.finish(a.id));
        let b = j.begin().unwrap();
        assert!(!j.finish(a.id));
        assert!(j.busy());
        assert!(j.finish(b.id));
        assert!(!j.busy());
    }
    #[test]
    fn rejects_partial_empty_oversized_and_cancelled_audio() {
        let c = AtomicBool::new(false);
        let mut out = vec![];
        assert_eq!(
            copy_audio(&b"abc"[..], &mut out, Some(3), &c, |_| {}).unwrap(),
            3
        );
        assert_eq!(out, b"abc");
        assert!(copy_audio(&b"abc"[..], vec![], Some(4), &c, |_| {}).is_err());
        assert!(copy_audio(&b"abc"[..], vec![], Some(2), &c, |_| {}).is_err());
        assert!(copy_audio(&b""[..], vec![], None, &c, |_| {}).is_err());
        assert!(copy_audio(&b"a"[..], vec![], Some(MAX_AUDIO + 1), &c, |_| {}).is_err());
        c.store(true, Ordering::Relaxed);
        assert!(copy_audio(&b"abc"[..], vec![], None, &c, |_| {}).is_err());
    }
}
