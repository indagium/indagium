#!/usr/bin/env python3
"""Create a deterministic single-log ZIP only under the synthetic fixture root."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

repo = Path(__file__).resolve().parent.parent
root = repo / 'build' / 'ram-poc' / 'fixtures'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--fixture', choices=['100k', '1m', '10m'], default='1m')
parser.add_argument('--grouped', action='store_true', help='replace four rows with one deterministic fatal stack (1m only)')
parser.add_argument('--video', action='store_true', help='add a generated 1080p test video and two background logs (1m only)')
args = parser.parse_args()
if args.grouped and args.fixture != '1m': parser.error('--grouped requires --fixture 1m')
if args.video and (args.fixture != '1m' or args.grouped): parser.error('--video requires --fixture 1m without --grouped')
for part in (repo / 'build', repo / 'build/ram-poc', root):
    if part.is_symlink() or not part.is_dir():
        raise ValueError(f'unsafe or missing fixture directory: {part}')
source = root / f'logcat-{args.fixture}.log'
expected_hashes = {
    '100k': 'f4f5b48a518d3e2556536ae3f4e236a19233e07986b4cde068563f81af65ff6c',
    '1m': 'f3b0a133e2042ff1493ddf32080acde46f85d7890bdf6816ac8240dd8566fa28',
    '10m': '2ef4686cc31db83e8d3d7fc79938c99eda1f64812e092074b2b6bfde735eda7e',
}
suffix = '-video' if args.video else '-grouped' if args.grouped else ''
target = root / f'logcat-{args.fixture}{suffix}.zip'
temp = root / f'.logcat-{args.fixture}{suffix}.{os.getpid()}.zip.tmp'
if root.resolve() != root or source.is_symlink() or not source.is_file() or source.resolve().parent != root:
    raise ValueError('unsafe or missing synthetic log')
with source.open('rb') as f:
    if hashlib.file_digest(f, 'sha256').hexdigest() != expected_hashes[args.fixture]:
        raise ValueError('source does not match the generated synthetic fixture')
if target.is_symlink() or temp.exists() or temp.is_symlink():
    raise ValueError('unsafe output')
video_temp = root / f'.synthetic-video.{os.getpid()}.tmp'
manifest = root / 'logcat-1m-video.json'
if args.video:
    background = root / 'logcat-100k.log'
    if background.is_symlink() or not background.is_file() or background.resolve().parent != root:
        raise ValueError('missing synthetic background log')
    with background.open('rb') as f:
        if hashlib.file_digest(f, 'sha256').hexdigest() != expected_hashes['100k']:
            raise ValueError('background does not match generated synthetic fixture')
    if video_temp.exists() or video_temp.is_symlink() or manifest.is_symlink():
        raise ValueError('unsafe video output')
    for name in ('background-a.log', 'background-b.log'):
        dest = root / name
        if dest.is_symlink(): raise ValueError('unsafe background output')
        shutil.copyfile(background, dest)
try:
    if args.video:
        subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=1920x1080:rate=30',
                        '-t', '5', '-an', '-c:v', 'mpeg4', '-q:v', '5', '-f', 'mp4', '-n', str(video_temp)], check=True)
        with video_temp.open('rb') as f:
            video_digest = hashlib.file_digest(f, 'sha256').hexdigest()
    with temp.open('xb') as raw, zipfile.ZipFile(raw, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        entry = zipfile.ZipInfo('synthetic.log', date_time=(2020, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        entry.external_attr = 0o600 << 16
        with source.open('rb') as src, archive.open(entry, 'w', force_zip64=True) as dst:
            if args.grouped:
                for _ in range(4):
                    if not src.readline(): raise ValueError('fixture has fewer than four rows')
                for index, message in enumerate(('FATAL EXCEPTION: main', 'Process: synthetic.app, PID: 1234',
                    'java.lang.IllegalStateException: synthetic crash', '    at synthetic.app.Main.run(Main.kt:42)')):
                    dst.write(f'11-03 10:00:00.{index:03d}  1234  1234 E AndroidRuntime: {message}\n'.encode('ascii'))
            shutil.copyfileobj(src, dst, length=1024 * 1024)
        if args.video:
            entry = zipfile.ZipInfo('synthetic.mp4', date_time=(2020, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_STORED
            entry.external_attr = 0o600 << 16
            with video_temp.open('rb') as src, archive.open(entry, 'w', force_zip64=True) as dst:
                shutil.copyfileobj(src, dst, length=1024 * 1024)
    os.replace(temp, target)
    if args.video:
        manifest.write_text(json.dumps({'synthetic_video_sha256': video_digest}))
finally:
    temp.unlink(missing_ok=True)
    video_temp.unlink(missing_ok=True)
with target.open('rb') as f:
    digest = hashlib.file_digest(f, 'sha256').hexdigest()
print(json.dumps({'path': str(target), 'bytes': target.stat().st_size, 'sha256': digest}))
