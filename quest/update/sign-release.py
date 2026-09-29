#!/usr/bin/env python3
"""Create exact-byte signed Quest update metadata after both assets are verified."""
import argparse, hashlib, json, re, subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--directory',type=Path,required=True)
p.add_argument('--private-key',type=Path,required=True)
p.add_argument('--version',required=True)
p.add_argument('--sequence',type=int,required=True)
p.add_argument('--apk-version-code',type=int,required=True)
p.add_argument('--apk-signer',required=True)
a=p.parse_args()
if not re.fullmatch(r'[0-9]{1,5}(\.[0-9]{1,5}){3}',a.version) or min(a.sequence,a.apk_version_code)<=0:
 p.error('invalid release version/sequence')
if not re.fullmatch(r'[a-f0-9]{64}',a.apk_signer):p.error('invalid signer digest')
def asset(filename):
 path=a.directory/filename
 size=path.stat().st_size
 if not 0<size<=768*1024*1024:raise ValueError('Invalid release asset size')
 with path.open('rb') as f:digest=hashlib.file_digest(f,'sha256').hexdigest()
 return dict(filename=filename,url=f'https://github.com/samelamin/vibertemis/releases/download/quest-preview-v{a.version}/{filename}',bytes=size,sha256=digest)
windows=asset(f'VibertemisVR-HostManager-Setup-{a.version}.exe')
android=asset(f'vibertemis-quest-preview-{a.version}.apk')
android.update(package='com.vibertemis.quest.preview.debug',version_code=a.apk_version_code,signer_sha256=a.apk_signer)
body=dict(schema=1,channel='quest-preview',sequence=a.sequence,version=a.version,native_protocol='20.14.1-vibertemis-pyro.1',assets=dict(windows=windows,android=android))
manifest=a.directory/'quest-update.json';signature=a.directory/'quest-update.json.sig'
manifest.write_bytes((json.dumps(body,indent=2,ensure_ascii=True)+'\n').encode('utf-8'))
subprocess.run(['openssl','dgst','-sha256','-sign',str(a.private_key),'-out',str(signature),str(manifest)],check=True)
if signature.stat().st_size!=384:raise ValueError('Expected RSA-3072 signature')
public=Path(__file__).with_name('public-key.pem')
subprocess.run(['openssl','dgst','-sha256','-verify',str(public),'-signature',str(signature),str(manifest)],check=True)
print('Created verified quest-update.json and detached signature.')
