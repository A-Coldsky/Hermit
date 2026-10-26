package dev.hermit.reef.conch;

import dev.hermit.common.pkg.ConchPackage;
import dev.hermit.common.pkg.VerifiedConch;
import java.security.PublicKey;
import org.springframework.stereotype.Component;

@Component
public class ConchPackageVerifier implements PackageVerifier {

    @Override
    public VerifiedConch verify(byte[] zip, PublicKey key) {
        return ConchPackage.readVerified(zip, key);
    }
}
