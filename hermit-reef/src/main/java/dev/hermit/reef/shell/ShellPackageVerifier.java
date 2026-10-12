package dev.hermit.reef.shell;

import dev.hermit.common.pkg.ShellPackage;
import dev.hermit.common.pkg.VerifiedShell;
import java.security.PublicKey;
import org.springframework.stereotype.Component;

@Component
public class ShellPackageVerifier implements PackageVerifier {

    @Override
    public VerifiedShell verify(byte[] zip, PublicKey key) {
        return ShellPackage.readVerified(zip, key);
    }
}
