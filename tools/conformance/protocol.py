"""Test-only process bridge. Candidate operations always execute in a JVM.

Python only orchestrates and verifies results; it never generates QR candidates.
The class path contains solely the candidate and this test driver. No reference
encoder, decoder, Python QR library, or Node package can enter the candidate JVM.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

JAVA = os.environ.get('SPECQR_JAVA', 'java')
JAR = None
DRIVER = Path(__file__).resolve().parents[2] / 'src/test/java/io/specqr/ConformanceDriver.java'
_COMPILED = {}
_TEMP = []


def configure(java=None, jar=None):
    global JAVA, JAR
    JAVA, JAR = java or os.environ.get('SPECQR_JAVA', 'java'), Path(jar).resolve() if jar else None


def _classpath(candidate):
    root = Path(candidate).resolve()
    inputs = [DRIVER] + ([JAR] if JAR else sorted((root / 'src/main/java').rglob('*.java')))
    fingerprint = tuple((str(p), hashlib.sha256(p.read_bytes()).hexdigest()) for p in inputs)
    key = (JAVA, fingerprint)
    if key not in _COMPILED:
        java = Path(shutil.which(JAVA) or JAVA).resolve()
        javac = java.with_name('javac')
        temporary = tempfile.TemporaryDirectory(prefix='specqr-java-conformance-')
        _TEMP.append(temporary)
        classes = Path(temporary.name)
        command = [str(javac), '-J-XX:ActiveProcessorCount=2', '--release', '17', '-encoding', 'UTF-8', '-d', str(classes)]
        if JAR:
            command += ['--class-path', str(JAR), str(DRIVER)]
        else:
            command += [str(p) for p in inputs if p.name != 'module-info.java']
        subprocess.run(command, check=True, capture_output=True, text=True, encoding='utf-8', timeout=120)
        _COMPILED[key] = str(classes) + (os.pathsep + str(JAR) if JAR else '')
    return _COMPILED[key]


def generate(candidate, requests, fault=None):
    command = [JAVA, '-XX:ActiveProcessorCount=2', '-Xmx512m', '-Dfile.encoding=UTF-8', '--class-path', _classpath(candidate), 'io.specqr.ConformanceDriver']
    env = os.environ.copy()
    for name in ('CLASSPATH', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'SPECQR_TEST_FAULT'):
        env.pop(name, None)
    if fault:
        env['SPECQR_TEST_FAULT'] = fault
    process = subprocess.run(command, input=''.join(json.dumps(r, ensure_ascii=True) + '\n' for r in requests),
                             capture_output=True, text=True, encoding='utf-8', env=env, timeout=900)
    if process.returncode:
        raise RuntimeError(f'Java candidate process failed ({process.returncode}): {process.stderr[-4000:]}')
    try:
        results = [json.loads(line) for line in process.stdout.splitlines()]
    except json.JSONDecodeError as error:
        raise RuntimeError(f'Invalid Java JSON-lines response: {process.stdout[:1000]!r}; stderr={process.stderr[-1000:]!r}') from error
    if len(results) != len(requests):
        raise RuntimeError(f'Java response count mismatch: {len(results)} != {len(requests)}')
    return results


def matrix_hash(matrix):
    return hashlib.sha256(''.join(matrix).encode('ascii')).hexdigest()


def verify_identity(candidate, identity, nonce):
    classpath = _classpath(candidate)
    expected_origin = str(JAR) if JAR else classpath
    if (identity.get('nonce') != nonce or identity.get('pid') == os.getpid()
            or identity.get('candidateClass') != 'io.specqr.SpecQr'
            or identity.get('classPath') != classpath
            or identity.get('module') != expected_origin
            or not identity.get('java') or not identity.get('packageFilesSha256')):
        raise RuntimeError(f'Candidate JVM/classpath identity check failed: {identity}')
