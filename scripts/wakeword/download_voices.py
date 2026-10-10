"""Explicitly authorized voice downloads; cleanup owns only files created by this run."""
from __future__ import annotations
import argparse
import hashlib
import json
import shutil
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LEDGER = ROOT / 'models/wakeword/voice_downloads.json'


def fetch_json(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def download(url, target, expected_size, expected_md5=None):
    if target.exists():
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + '.part')
    md5 = hashlib.md5()
    with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as output:
        while chunk := response.read(1024*1024):
            output.write(chunk)
            md5.update(chunk)
    if temporary.stat().st_size != expected_size or (expected_md5 and md5.hexdigest() != expected_md5):
        temporary.unlink()
        raise ValueError(f'Incomplete or corrupt voice: {target}')
    temporary.replace(target)
    ledger = json.loads(LEDGER.read_text()) if LEDGER.exists() else []
    ledger.append({'path': str(target.relative_to(ROOT)), 'size': expected_size,
                   'sha256': hashlib.sha256(target.read_bytes()).hexdigest(), 'url': url})
    LEDGER.parent.mkdir(parents=True, exist_ok=True)
    LEDGER.write_text(json.dumps(ledger, indent=2), encoding='utf-8')
    print(f'downloaded {target.name}', flush=True)


def cleanup():
    if not LEDGER.exists():
        return
    rows = json.loads(LEDGER.read_text())
    # Attribution survives removal of the temporary synthesis weights/configs.
    cards = ROOT / 'scripts/wakeword/reports/voice_licenses'
    cards.mkdir(parents=True, exist_ok=True)
    for card in (ROOT / 'models/voice/piper').glob('*.MODEL_CARD'):
        shutil.copyfile(card, cards / card.name)
    for row in rows:
        target = (ROOT / row['path']).resolve()
        allowed = [ROOT/'models/voice/piper', ROOT/'models/tts/supertonic3/voice_styles']
        if not any(target.is_relative_to(p.resolve()) for p in allowed):
            raise ValueError('Download ledger target escaped voice folders')
        if target.exists():
            if hashlib.sha256(target.read_bytes()).hexdigest() != row['sha256']:
                raise ValueError(f'Voice changed since download; preserve it: {target}')
            target.unlink()
            print(f'removed downloaded voice {target.name}', flush=True)
    LEDGER.rename(LEDGER.with_name('voice_downloads_cleaned.json'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--download', action='store_true')
    parser.add_argument('--cleanup', action='store_true')
    args = parser.parse_args()
    if args.cleanup:
        cleanup()
        return
    if not args.download:
        parser.error('Use --download only after permission; or --cleanup after training')
    revision = fetch_json('https://huggingface.co/api/models/rhasspy/piper-voices')['sha']
    catalog = fetch_json(f'https://huggingface.co/rhasspy/piper-voices/raw/{revision}/voices.json')
    for voice in catalog.values():
        if voice['language']['family'] != 'es':
            continue
        for relative, info in voice['files'].items():
            filename = Path(relative).name
            if filename == 'MODEL_CARD':
                filename = voice['key'] + '.MODEL_CARD'
            target = ROOT/'models/voice/piper'/filename
            download(f'https://huggingface.co/rhasspy/piper-voices/resolve/{revision}/{relative}',
                     target, info['size_bytes'], info['md5_digest'])
    revision = '724fb5abbf5502583fb520898d45929e62f02c0b'
    for info in fetch_json(f'https://huggingface.co/api/models/Supertone/supertonic-3/tree/{revision}/voice_styles'):
        target = ROOT/'models/tts/supertonic3'/info['path']
        download(f'https://huggingface.co/Supertone/supertonic-3/resolve/{revision}/{info["path"]}', target, info['size'])


if __name__ == '__main__':
    main()
