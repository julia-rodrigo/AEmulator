# Test-build signing key

If `aemu-test-debug.keystore` is in this folder, **debug** builds (for example `assembleCloneDebug`) are signed with
it when no release key is set up (`keystore.properties`). Every machine that has the file then signs with the same
key, so a new test build installs over the previous one as an update and the imported firmware stays.

The key file is not in git (`*.keystore` is ignored): keep a copy and put it here before building. It is a throwaway
Android debug key (store and key password `android`, alias `androiddebugkey`), not the release key, and must never
sign a release. Without it, debug builds use the machine's own debug key and cannot update a build signed by another.

SHA-256 of the certificate of the current test key:
`7B:1C:FB:0A:B5:15:70:8F:F1:58:A3:BA:10:A4:99:8B:56:25:66:14:D7:CF:71:91:0F:A2:CE:9A:05:2E:81:95`
