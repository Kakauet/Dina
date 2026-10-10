"""Temporary voice cleanup preserves preexisting models and attribution; no network."""
import contextlib
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import download_voices


class CleanupTest(unittest.TestCase):
    def test_only_owned_downloads_are_removed_and_cards_are_retained(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            voices = root / 'models/voice/piper'
            voices.mkdir(parents=True)
            previous = voices / 'previous.onnx'
            previous.write_bytes(b'previous model')
            new = voices / 'new.onnx'
            new.write_bytes(b'new model')
            card = voices / 'new.MODEL_CARD'
            card.write_bytes(b'Attribution and license')
            ledger = root / 'models/wakeword/voice_downloads.json'
            ledger.parent.mkdir(parents=True)
            ledger.write_text(json.dumps([
                dict(path=str(path.relative_to(root)), sha256=hashlib.sha256(path.read_bytes()).hexdigest())
                for path in (new, card)
            ]))
            with patch.object(download_voices, 'ROOT', root), patch.object(download_voices, 'LEDGER', ledger):
                with contextlib.redirect_stdout(io.StringIO()):
                    download_voices.cleanup()
            self.assertEqual(previous.read_bytes(), b'previous model')
            self.assertFalse(new.exists())
            self.assertFalse(card.exists())
            self.assertEqual((root / 'scripts/wakeword/reports/voice_licenses/new.MODEL_CARD').read_bytes(),
                             b'Attribution and license')
            self.assertTrue(ledger.with_name('voice_downloads_cleaned.json').exists())

    def test_modified_download_is_preserved(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            voice = root / 'models/voice/piper/new.onnx'
            voice.parent.mkdir(parents=True)
            voice.write_bytes(b'changed by user')
            ledger = root / 'models/wakeword/voice_downloads.json'
            ledger.parent.mkdir(parents=True)
            ledger.write_text(json.dumps([dict(path=str(voice.relative_to(root)), sha256='old hash')]))
            with patch.object(download_voices, 'ROOT', root), patch.object(download_voices, 'LEDGER', ledger):
                with self.assertRaisesRegex(ValueError, 'changed since download'):
                    download_voices.cleanup()
            self.assertEqual(voice.read_bytes(), b'changed by user')


if __name__ == '__main__':
    unittest.main()
