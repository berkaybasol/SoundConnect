"""Record only public CI identity and checked-out source hashes before any build."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]


def git(*args):
    return subprocess.check_output(['git', '-C', str(root), *args])


checkout = git('rev-parse', 'HEAD').decode().strip()
if checkout != os.environ['GITHUB_SHA']:
    raise SystemExit('Checkout SHA differs from the event SHA')
event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text(encoding='utf-8'))
pr = event.get('pull_request', {})
files = []
for record in git('ls-tree', '-rz', '--full-tree', 'HEAD').split(b'\0'):
    if not record:
        continue
    metadata, name = record.split(b'\t', 1)
    mode, kind, blob = metadata.decode().split()
    path = name.decode('utf-8')
    if kind != 'blob':
        raise SystemExit('Unsupported non-blob source: ' + path)
    files.append({'path': path, 'mode': mode, 'gitBlob': blob,
                  'checkoutSha256': hashlib.sha256((root / path).read_bytes()).hexdigest()})
result = {
    'repository': os.environ['GITHUB_REPOSITORY'],
    'runId': os.environ['GITHUB_RUN_ID'], 'attempt': os.environ['GITHUB_RUN_ATTEMPT'],
    'job': os.environ['GITHUB_JOB'], 'event': os.environ['GITHUB_EVENT_NAME'],
    'ref': os.environ['GITHUB_REF'], 'workflowRef': os.environ['GITHUB_WORKFLOW_REF'],
    'workflowSha': os.environ['GITHUB_WORKFLOW_SHA'],
    'checkoutSha': checkout, 'checkoutTree': git('rev-parse', 'HEAD^{tree}').decode().strip(),
    'parents': git('show', '-s', '--format=%P', 'HEAD').decode().strip().split(),
    'prNumber': pr.get('number'), 'baseSha': pr.get('base', {}).get('sha'),
    'headSha': pr.get('head', {}).get('sha'), 'files': files,
}
output = Path(sys.argv[1])
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
print(f"Source provenance: {checkout} tree={result['checkoutTree']} files={len(files)}")
