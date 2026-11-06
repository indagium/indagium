#!/usr/bin/env python3
"""Run isolated Compose harness launches, save raw stdout and RSS observations."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import statistics
import re
import platform
import shutil
import subprocess
import tempfile
import time
import zipfile

REPO = Path(__file__).resolve().parent.parent
BASE = REPO / 'build/ram-poc'
os.chdir(REPO)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--workload', choices=['ui', 'model', 'direct', 'main', 'archive'], default='ui')
parser.add_argument('--version', choices=['baseline', 'current', 'archive-pre-fix'], required=True)
parser.add_argument('--fixture', choices=['100k', '1m', '10m'], default='100k')
parser.add_argument('--diagnostic', choices=['ordinary', 'forcegc', 'nmt', 'jfr'], default='ordinary')
parser.add_argument('--interop-blending', choices=['default', 'false', 'true'], default='default')
parser.add_argument('--repetitions', type=int, default=3)
parser.add_argument('--heaps', nargs='+', choices=['default', '32m', '64m', '128m'], default=['default', '32m', '64m', '128m'])
parser.add_argument('--modes', nargs='+', choices=['clean', 'restore'], default=['clean', 'restore'])
parser.add_argument('--archive-cycles', type=int, default=6)
parser.add_argument('--archive-hold-snapshots', action='store_true')
parser.add_argument('--archive-grouped', action='store_true')
parser.add_argument('--archive-histogram', action='store_true')
parser.add_argument('--archive-video', action='store_true')
args = parser.parse_args()
if args.repetitions < 1: parser.error('repetitions must be positive')
if not 1 <= args.archive_cycles <= 20: parser.error('--archive-cycles must be between 1 and 20')
if args.archive_hold_snapshots and args.workload != 'archive': parser.error('--archive-hold-snapshots requires --workload archive')
if args.archive_grouped and (args.workload != 'archive' or args.fixture != '1m'):
    parser.error('--archive-grouped requires --workload archive --fixture 1m')
if args.archive_histogram and (args.workload != 'archive' or args.diagnostic != 'forcegc'):
    parser.error('--archive-histogram requires --workload archive --diagnostic forcegc')
if args.archive_video and (args.workload != 'archive' or args.fixture != '1m' or args.archive_grouped):
    parser.error('--archive-video requires --workload archive --fixture 1m without --archive-grouped')
if args.workload != 'main' and args.interop_blending != 'default':
    parser.error('--interop-blending is only available for the Main diagnostic')
fixture = BASE / 'fixtures' / ('logcat-' + args.fixture + ('-video' if args.archive_video else '-grouped' if args.archive_grouped else '') + ('.zip' if args.workload == 'archive' else '.log'))
if args.workload != 'main':
    for part in [REPO / 'build', BASE, BASE / 'fixtures', fixture]:
        if part.is_symlink(): raise ValueError('symlink in fixture path')
    if not fixture.is_file() or fixture.resolve().parent != (BASE / 'fixtures').resolve(): raise ValueError('missing fixture')
    if args.workload == 'archive':
        expected = {
            'logcat-100k.zip': 'f4f5b48a518d3e2556536ae3f4e236a19233e07986b4cde068563f81af65ff6c',
            'logcat-1m.zip': 'f3b0a133e2042ff1493ddf32080acde46f85d7890bdf6816ac8240dd8566fa28',
            'logcat-1m-grouped.zip': '97f671c46b6208c33633fed83b0334d5d30548c9a95a5702968c574bfb6a9b0a',
            'logcat-1m-video.zip': 'f3b0a133e2042ff1493ddf32080acde46f85d7890bdf6816ac8240dd8566fa28',
            'logcat-10m.zip': '2ef4686cc31db83e8d3d7fc79938c99eda1f64812e092074b2b6bfde735eda7e',
        }
        with zipfile.ZipFile(fixture) as archive:
            if archive.namelist() != (['synthetic.log', 'synthetic.mp4'] if args.archive_video else ['synthetic.log']):
                raise ValueError('archive must contain only the generated synthetic log')
            with archive.open('synthetic.log') as f:
                if hashlib.file_digest(f, 'sha256').hexdigest() != expected[fixture.name]:
                    raise ValueError('archive does not match the generated synthetic fixture')
            if args.archive_video:
                manifest = BASE / 'fixtures/logcat-1m-video.json'
                if manifest.is_symlink() or not manifest.is_file(): raise ValueError('missing video fixture manifest')
                video_hash = json.loads(manifest.read_text())['synthetic_video_sha256']
                with archive.open('synthetic.mp4') as f:
                    if hashlib.file_digest(f, 'sha256').hexdigest() != video_hash:
                        raise ValueError('video does not match generated fixture')
                for name in ('background-a.log', 'background-b.log'):
                    background = BASE / 'fixtures' / name
                    if background.is_symlink() or not background.is_file() or background.resolve().parent != fixture.parent:
                        raise ValueError('unsafe or missing background fixture')
                    with background.open('rb') as f:
                        if hashlib.file_digest(f, 'sha256').hexdigest() != expected['logcat-100k.zip']:
                            raise ValueError('background does not match generated fixture')
elif args.diagnostic == 'forcegc':
    parser.error('Main has no forced-GC sampling hook')
cp = json.loads((BASE / 'classpath.json').read_text())['main' if args.workload == 'main' else 'test']
if args.version in ('baseline', 'archive-pre-fix'):
    snapshot = 'baseline' if args.version == 'baseline' else 'archive-pre-fix'
    replacements = {'build/classes/kotlin/desktop/main': f'build/ram-poc/{snapshot}/classes/kotlin/desktop/main',
                    'build/processedResources/desktop/main': f'build/ram-poc/{snapshot}/processedResources/desktop/main'}
    if any(str(REPO / src) not in cp or not (REPO / dst).is_dir() for src, dst in replacements.items()):
        raise ValueError(f'{snapshot} snapshot is incomplete or missing from the classpath')
    cp = [next((str(REPO / dst) for src, dst in replacements.items() if x == str(REPO / src)), x) for x in cp]
classpath = os.pathsep.join(cp)
env = {k: os.environ[k] for k in ('PATH', 'TMPDIR', 'LANG', 'LC_ALL', 'DISPLAY', 'XAUTHORITY', 'WAYLAND_DISPLAY') if k in os.environ}
# Do not inherit credentials, proxy settings, Java options, or application-specific settings.
results = BASE / 'results'
if results.is_symlink(): raise ValueError('results directory is a symlink')
results.mkdir(parents=True, exist_ok=True)
fixture_label = 'not-opened' if args.workload == 'main' else args.fixture
run_dir = Path(tempfile.mkdtemp(prefix=f'{args.version}-{fixture_label}-{args.workload}-{args.diagnostic}-', dir=results))
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else 'java'
version_result = subprocess.run([java, '-version'], capture_output=True, text=True, env=env, timeout=30)
if version_result.returncode:
    raise RuntimeError(f'java -version failed: {version_result.stderr}')
metadata = {'version': args.version, 'fixture': fixture_label, 'diagnostic': args.diagnostic, 'workload': args.workload,
    'interop_blending': args.interop_blending,
    'archive_cycles': args.archive_cycles if args.workload == 'archive' else None,
    'archive_hold_snapshots': args.archive_hold_snapshots if args.workload == 'archive' else None,
    'archive_grouped': args.archive_grouped if args.workload == 'archive' else None,
    'archive_histogram': args.archive_histogram if args.workload == 'archive' else None,
    'archive_video': args.archive_video if args.workload == 'archive' else None,
    'platform': platform.platform(), 'python': platform.python_version(), 'java_version': version_result.stdout + version_result.stderr,
    'heaps': args.heaps, 'modes': ['main'] if args.workload == 'main' else args.modes, 'repetitions': args.repetitions,
    'fixture_sha256': None if args.workload == 'main' else hashlib.file_digest(fixture.open('rb'), 'sha256').hexdigest(), 'commands': {}}

def save_metadata():
    (run_dir / 'metadata.json').write_text(json.dumps(metadata, indent=2))

save_metadata()

def diagnostic_command(cmd, path):
    try:
        report = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=30)
        path.write_text(report.stdout + report.stderr + f'\nexit_code={report.returncode}\n')
    except subprocess.TimeoutExpired:
        path.write_text('Diagnostic command timed out after 30 seconds.\n')

def run(home, mode, heap, name):
    cmd = [java, '-XX:MaxRAMPercentage=50', '-XX:G1PeriodicGCInterval=30000', '-XX:MaxHeapFreeRatio=30', '-XX:MinHeapFreeRatio=10', '-Duser.home=' + str(home), '-Djava.util.prefs.userRoot=' + str(home / 'prefs'),
           '-Djava.util.prefs.systemRoot=' + str(home / 'prefs-system')]
    if heap != 'default': cmd += ['-Xms' + heap]
    if args.diagnostic == 'forcegc': cmd += ['-Dindagium.ram.forceGc=true']
    if args.diagnostic == 'nmt': cmd += ['-XX:NativeMemoryTracking=summary']
    if args.diagnostic == 'jfr':
        cmd += [f'-XX:StartFlightRecording=filename={run_dir / (name + ".jfr")},settings=profile,dumponexit=true']
    if args.workload == 'archive':
        cmd += [f'-Dindagium.ram.archive.cycles={args.archive_cycles}']
        if args.archive_hold_snapshots: cmd += ['-Dindagium.ram.archive.holdSnapshots=true']
        if args.archive_histogram: cmd += ['-Dindagium.ram.archive.histogram=true']
        if args.archive_video: cmd += ['-Dindagium.ram.archive.video=true']
        if args.diagnostic == 'nmt': cmd += ['-Dindagium.ram.archive.footprint=true']
    if args.workload == 'main':
        if args.interop_blending != 'default':
            cmd += ['-Dcompose.interop.blending=' + args.interop_blending]
    cmd += ['-Dindagium.ram.launchEpochMs=' + str(int(time.time() * 1000)), '-cp', classpath]
    if args.workload == 'ui': cmd += ['com.indagium.ram.RamPocBenchmarkWindow', str(home), str(fixture), mode]
    elif args.workload == 'model': cmd += ['com.indagium.ram.RamPocBenchmarkModel', args.fixture]
    elif args.workload == 'direct': cmd += ['com.indagium.ram.RamPocBenchmarkDirect', args.fixture]
    elif args.workload == 'archive': cmd += ['com.indagium.ram.RamPocArchiveCycles', str(home), str(fixture)]
    else: cmd += ['MainKt']
    metadata['commands'][name] = cmd
    save_metadata()
    started = time.monotonic()
    with (run_dir / (name + '.log')).open('w') as out, (run_dir / (name + '.rss.csv')).open('w') as rss:
        rss.write('elapsed_s,rss_kib\n')
        proc = subprocess.Popen(cmd, env=env, stdout=out, stderr=subprocess.STDOUT)
        planned_stop = False
        try:
            milestones = {10, 60}
            while proc.poll() is None:
                elapsed = time.monotonic() - started
                if args.workload == 'main' and elapsed >= 65:
                    planned_stop = True
                    break
                if elapsed >= 900:
                    raise TimeoutError(f'{name} exceeded its 900-second limit; see {run_dir / (name + ".log")}')
                for milestone in sorted(milestones.copy()):
                    if elapsed >= milestone:
                        milestones.remove(milestone)
                        if args.diagnostic == 'nmt':
                            jcmd = str(Path(java).with_name('jcmd')) if '/' in java else 'jcmd'
                            diagnostic_command([jcmd, str(proc.pid), 'VM.native_memory', 'summary'],
                                               run_dir / f'{name}.nmt-{milestone}s.txt')
                        if args.diagnostic == 'nmt' and platform.system() == 'Darwin' and shutil.which('footprint', path=env.get('PATH')):
                            diagnostic_command(['footprint', str(proc.pid)], run_dir / f'{name}.footprint-{milestone}s.txt')
                try:
                    sample = subprocess.run(['ps', '-o', 'rss=', '-p', str(proc.pid)], capture_output=True, text=True, env=env, timeout=30)
                    if sample.returncode == 0:
                        rss.write(f'{time.monotonic()-started:.3f},{sample.stdout.strip()}\n')
                        rss.flush()
                except (subprocess.TimeoutExpired, OSError) as error:
                    (run_dir / f'{name}.rss-error.txt').write_text(f'RSS sampling unavailable: {error}\n')
                time.sleep(1)
            code = proc.returncode
        finally:
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait()
    if args.workload == 'main' and not planned_stop:
        raise RuntimeError(f'{name} exited before the planned 65-second stop with {code}; see {run_dir / (name + ".log")}')
    if args.workload != 'main' and code:
        raise RuntimeError(f'{name} failed with {code}; see {run_dir / (name + ".log")}')

for heap in args.heaps:
    for mode in (args.modes if args.workload == 'ui' else [args.workload if args.workload in ('main', 'archive') else 'model']):
        for iteration in range(1, args.repetitions + 1):
            with tempfile.TemporaryDirectory(prefix='indagium-ram-poc-') as directory:
                home = Path(directory).resolve()
                if args.workload == 'main':
                    temp_root = Path(tempfile.gettempdir()).resolve()
                    if Path(directory).is_symlink() or home == temp_root or not home.is_relative_to(temp_root) or not home.is_dir():
                        raise ValueError('Main requires a fresh canonical temporary home')
                name = f'{heap}-{mode}-{iteration}'
                if args.workload == 'ui' and mode == 'restore': run(home, 'prepare', heap, name + '-prepare')
                run(home, mode, heap, name)
                print(name, flush=True)
summary = {}
for path in sorted(run_dir.glob('*.log')):
    if '-prepare.log' in path.name: continue
    key = path.stem.rsplit('-', 1)[0]
    for line in path.read_text(errors='replace').splitlines():
        if not line.startswith(('RAM_POC ', 'RAM_POC_UI ', 'RAM_POC_UI_DRAW ', 'RAM_POC_UI_TIMING ', 'RAM_POC_ARCHIVE ')): continue
        fields = dict(re.findall(r'(\w+)=([^ ]+)', line))
        phase = fields.pop('phase')
        if 'cycle' in fields: phase += '_cycle' + fields.pop('cycle')
        for metric, value in fields.items():
            try:
                number = int(value)
                summary.setdefault(f'{key} {phase} {metric}', []).append(number)
            except ValueError: pass
for path in sorted(run_dir.glob('*.rss.csv')):
    if '-prepare.rss.csv' in path.name: continue
    key = path.name.removesuffix('.rss.csv').rsplit('-', 1)[0]
    samples = []
    for line in path.read_text().splitlines()[1:]:
        try:
            t, rss = line.split(',')
            samples.append((float(t), int(rss)))
        except ValueError: pass
    if samples:
        summary.setdefault(f'{key} process peak_rss_kib', []).append(max(v for _, v in samples))
        for second in (10, 60):
            if samples[-1][0] >= second:
                _, value = min(samples, key=lambda pair: abs(pair[0] - second))
                summary.setdefault(f'{key} process rss_{second}s_kib', []).append(value)
with (run_dir / 'medians.json').open('w') as f:
    json.dump({key: {'n': len(values), 'median': statistics.median(values)} for key, values in summary.items()}, f, indent=2, sort_keys=True)
print('results:', run_dir)
