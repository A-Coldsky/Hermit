package dev.hermit.reef.conch;

import dev.hermit.common.pkg.VerifiedConch;
import java.security.PublicKey;

public interface PackageVerifier {

    VerifiedConch verify(byte[] zip, PublicKey key);
}
