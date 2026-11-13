"""Apply the r71 owner's 9 GiB heap to the dedicated launcher's argument file."""

def heap_arguments(data: bytes) -> bytes:
    lines = data.decode('utf-8-sig').splitlines()
    limits = [i for i, line in enumerate(lines) if line.strip().startswith('-Xmx')]
    if len(limits) != 1 or lines[limits[0]].strip() not in ('-Xmx6G', '-Xmx9G'):
        raise ValueError('Unrecognized server heap in supplied arguments')
    lines[limits[0]] = '-Xmx9G'
    for setting in ('-XX:+UnlockExperimentalVMOptions', '-XX:G1MaxNewSizePercent=20'):
        if setting not in lines:
            lines.append(setting)
    return ('\n'.join(lines) + '\n').encode('utf8')
