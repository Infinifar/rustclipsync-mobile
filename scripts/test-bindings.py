"""Host UniFFI ABI smoke tests; synthetic memory adapters only, no OS clipboard or network."""
import asyncio
import gc
from pathlib import Path
import sys
import threading
import unittest
import weakref

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "build" / "python"))
from rustclipsync_mobile_ffi import AdapterError, MobileConfig, MobileError, MobileSession, Platform, SensitivePolicy


class MemoryPlatform(Platform):
    def __init__(self, *, fail=False, unexpected=False):
        self.ciphertext = None
        self.threads = set()
        self.fail = fail
        self.unexpected = unexpected

    def load_state(self):
        self.threads.add(threading.get_ident())
        if self.unexpected:
            raise RuntimeError("synthetic-only diagnostic")
        if self.fail:
            raise AdapterError.Storage()
        return self.ciphertext

    def save_state(self, ciphertext):
        self.threads.add(threading.get_ident())
        self.ciphertext = ciphertext

    def clipboard_snapshot(self):
        raise AssertionError("metadata operation accessed clipboard")

    def clipboard_write(self, value):
        raise AssertionError("metadata operation wrote clipboard")


def config(key=b"\x07" * 32):
    return MobileConfig(server="https://synthetic.invalid/", username="synthetic", password="synthetic", state_key=key, sensitive_policy=SensitivePolicy.DISABLED)


class BindingSmokeTests(unittest.IsolatedAsyncioTestCase):
    async def test_callbacks_encryption_shutdown_and_release(self):
        platform = MemoryPlatform()
        session = MobileSession(config(), platform)
        try:
            self.assertFalse((await session.status()).paired)
            self.assertEqual(platform.ciphertext[:4], b"RCS1")
            self.assertNotIn(threading.get_ident(), platform.threads)
            self.assertEqual(len(platform.threads), 1)
            session.request_close()
            session.request_close()
            await asyncio.gather(session.shutdown(), session.shutdown())
            with self.assertRaises(MobileError.Closed):
                await session.status()
            reference = weakref.ref(platform)
            del platform
            gc.collect()
            self.assertIsNone(reference(), "shutdown retained foreign platform callbacks")
        finally:
            await session.shutdown()

    async def test_callback_storage_error_is_typed_and_sanitized(self):
        session = MobileSession(config(), MemoryPlatform(fail=True))
        try:
            with self.assertRaises(MobileError.Storage):
                await session.status()
        finally:
            await session.shutdown()

    async def test_unexpected_foreign_exception_becomes_safe_storage_error(self):
        session = MobileSession(config(), MemoryPlatform(unexpected=True))
        try:
            with self.assertRaises(MobileError.Storage) as caught:
                await session.status()
            self.assertNotIn("synthetic-only diagnostic", str(caught.exception))
        finally:
            await session.shutdown()

    async def test_invalid_key_never_starts_callbacks(self):
        with self.assertRaises(MobileError.InvalidInput):
            MobileSession(config(b"short"), MemoryPlatform())


if __name__ == "__main__":
    unittest.main(verbosity=2)
