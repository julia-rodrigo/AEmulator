#!/bin/sh
# Host test of the shim's binder release forwarding (x86-64 Linux, gcc): a fake binder driver plays the app,
# mediaserver and binderd. Buffers live below 4 GB (MAP_32BIT) so the shim's 32-bit pointer fields hold them.
set -e
cd "$(dirname "$0")"
T=${TMPDIR:-/tmp}/aemushim-test; mkdir -p "$T"
python3 - "$T/region.c" <<'PY'
import sys
s = open('../aemushim.c').read()
a = s.index('#define BINDER_WRITE_READ_'); b = s.index('/*\n * Host paths leak into the guest')
open(sys.argv[1], 'w').write(s[a:b])
PY
gcc -O1 -no-pie -fno-pie -w -I"$T" -o "$T/t" binder_release_test.c
"$T/t"
