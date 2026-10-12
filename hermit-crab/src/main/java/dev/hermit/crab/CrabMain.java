package dev.hermit.crab;

import java.util.function.Function;

import dev.hermit.crab.cli.CrabCommand;

public final class CrabMain {
    private CrabMain() {
    }

    public static void main(String[] args) {
        Function<String, String> environment = System::getenv;
        int code = new CrabCommand().run(args, environment, System.in, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }
}
