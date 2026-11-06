#!/usr/bin/env python3
"""Generate deterministic logcat fixtures only in this checkout's designated fixture directory."""
import argparse
import hashlib
import json
import os
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ROOT = REPO / 'build' / 'ram-poc' / 'fixtures'
SIZES = {'100k': 100_000, '1m': 1_000_000, '10m': 10_000_000}


def safe_root():
    # Reject symlinks in every existing path component before creating anything.
    p = REPO
    for component in ('build', 'ram-poc', 'fixtures'):
        p = p / component
        if p.is_symlink():
            raise ValueError(f'symlink in fixture path: {p}')
        p.mkdir(exist_ok=True)
    if ROOT.resolve() != ROOT:
        raise ValueError('fixture root escaped the checkout')
    return ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sizes', nargs='+', choices=SIZES)
    args = parser.parse_args()
    root = safe_root()
    for name in dict.fromkeys(args.sizes):
        target = root / f'logcat-{name}.log'
        temp = root / f'.logcat-{name}.{os.getpid()}.tmp'
        if target.is_symlink() or temp.exists() or temp.is_symlink():
            raise ValueError('refusing symlink or existing temporary path')
        digest = hashlib.sha256()
        try:
            with temp.open('xb', buffering=1024 * 1024) as out:
                for i in range(SIZES[name]):
                    # Threadtime format, stable across locales and hosts. 10% keyword matches.
                    msg = 'request denied' if i % 10 == 0 else 'frame rendered ok'
                    if i % 997 == 0:
                        msg = 'java.lang.IllegalStateException: synthetic failure'
                    elif i % 997 in (1, 2, 3):
                        msg = f'    at synthetic.module.Worker.run(Worker.kt:{i % 123 + 1})'
                    elif i % 17 == 0:
                        msg += ' café Київ 漢字 ' + ('x' * (i % 97))
                    line = (f'11-03 10:{(i // 60000) % 60:02d}:{(i // 1000) % 60:02d}.{i % 1000:03d} '
                            f'{1000 + i % 7:5d} {1000 + i % 11:5d} I '
                            f'PerfTag{i % 16}: {msg} id={i:08d}\n').encode('utf-8')
                    out.write(line)
                    digest.update(line)
            os.replace(temp, target)
        finally:
            temp.unlink(missing_ok=True)
        print(json.dumps({'path': str(target), 'rows': SIZES[name], 'bytes': target.stat().st_size,
                          'sha256': digest.hexdigest()}, sort_keys=True))
        switch = root / 'logcat-switch.log'
        if switch.is_symlink(): raise ValueError('switch fixture is a symlink')
        switch_tmp = root / f'.logcat-switch.{os.getpid()}.tmp'
        try:
            with target.open('rb') as source, switch_tmp.open('xb') as dest:
                for _ in range(1000): dest.write(source.readline())
            os.replace(switch_tmp, switch)
        finally:
            switch_tmp.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
