package dev.hermit.reef.shell;

import dev.hermit.common.pkg.VerifiedShell;
import java.security.PublicKey;

public interface PackageVerifier {

    VerifiedShell verify(byte[] zip, PublicKey key);
}
