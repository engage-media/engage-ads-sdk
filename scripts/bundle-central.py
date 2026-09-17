#!/usr/bin/env python3
"""Validate a Maven staging repository and create a Sonatype Central bundle.

Only reads staging files; never uploads. Checksums in the bundle are calculated
from the actual artifact bytes rather than trusting Gradle's sidecars.
"""
import argparse
import hashlib
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile


def bundle(repository: Path, output: Path) -> int:
    repository = repository.resolve()
    poms = sorted(repository.rglob('*.pom'))
    if not poms:
        raise ValueError('No Maven publications found')
    entries = {}
    for pom in poms:
        root = ET.fromstring(pom.read_bytes())
        ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
        def field(name):
            return root.findtext(f'm:{name}', namespaces=ns)
        group, artifact, version = field('groupId'), field('artifactId'), field('version')
        if not all((group, artifact, version)) or version.endswith('SNAPSHOT'):
            raise ValueError(f'Invalid release coordinates: {pom.name}')
        if group != 'com.github.engage-media':
            raise ValueError(f'Unexpected namespace: {group}')
        expected = Path(*group.split('.')) / artifact / version
        if pom.parent.relative_to(repository) != expected:
            raise ValueError(f'Coordinate/path mismatch: {pom.name}')
        for name in ('name', 'description', 'url', 'licenses', 'developers', 'scm'):
            if root.find(f'm:{name}', ns) is None:
                raise ValueError(f'Missing Central POM metadata {name}: {pom.name}')
        licenses = root.findall('m:licenses/m:license', ns)
        if not licenses or any(not license.findtext('m:name', namespaces=ns, default='').strip()
                               or not license.findtext('m:url', namespaces=ns, default='').startswith('https://')
                               for license in licenses):
            raise ValueError(f'Explicit release license name and HTTPS URL are required: {pom.name}')
        prefix = f'{artifact}-{version}'
        required = [pom, pom.parent / f'{prefix}.aar',
                    pom.parent / f'{prefix}-sources.jar', pom.parent / f'{prefix}-javadoc.jar']
        for path in required:
            if not path.is_file() or path.stat().st_size == 0:
                raise ValueError(f'Missing or empty artifact: {path.name}')
        artifacts = [p for p in sorted(pom.parent.iterdir())
                     if p.name.startswith(prefix + '.') or p.name.startswith(prefix + '-')]
        artifacts = [p for p in artifacts if p.suffix in ('.pom', '.aar', '.jar', '.module')]
        for path in artifacts:
            signature = path.with_name(path.name + '.asc')
            if not signature.is_file() or b'BEGIN PGP SIGNATURE' not in signature.read_bytes():
                raise ValueError(f'Missing armored signature: {path.name}')
            for item in (path, signature):
                name = item.relative_to(repository).as_posix()
                data = item.read_bytes()
                entries[name] = data
                for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
                    entries[f'{name}.{algorithm}'] = hashlib.new(algorithm, data).hexdigest().encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    return len(poms)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('repository', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    try:
        count = bundle(args.repository, args.output)
    except (ValueError, ET.ParseError) as exc:
        print(f'Refusing bundle: {exc}', file=sys.stderr)
        sys.exit(1)
    print(f'Bundled {count} signed publications into {args.output}; no upload performed.')
