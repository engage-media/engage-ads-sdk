#!/usr/bin/env python3
"""Upload a signed bundle using Central's USER_MANAGED (non-publishing) mode."""
import base64
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request
import uuid


def main():
    if len(sys.argv) != 2:
        raise SystemExit('Usage: upload-central.py bundle.zip')
    username = os.environ.get('CENTRAL_TOKEN_USERNAME')
    password = os.environ.get('CENTRAL_TOKEN_PASSWORD')
    if not username or not password:
        raise SystemExit('Central token username/password environment variables are required')
    path = Path(sys.argv[1])
    token = base64.b64encode(f'{username}:{password}'.encode()).decode()
    boundary = 'engage-' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="central-bundle.zip"\r\n'
            'Content-Type: application/octet-stream\r\n\r\n').encode() + path.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
    request = urllib.request.Request(
        'https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED', data=body,
        headers={'Authorization': f'Bearer {token}', 'Content-Type': f'multipart/form-data; boundary={boundary}'},
        method='POST')
    # Deliberately no automatic retry: a timed-out upload might already exist.
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            deployment = response.read().decode().strip()
    except urllib.error.HTTPError as exc:
        raise SystemExit(f'Central upload failed with HTTP {exc.code}; no automatic retry') from None
    except urllib.error.URLError:
        raise SystemExit('Central upload outcome is uncertain; inspect the portal before retrying') from None
    print(f'Central deployment: {deployment}. Validate and publish manually in the Central portal.')


if __name__ == '__main__':
    main()
