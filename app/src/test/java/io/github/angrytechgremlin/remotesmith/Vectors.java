package io.github.angrytechgremlin.remotesmith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/** tools/testdata/codec_vectors.txt: name, Pronto and the remote code tools/ircodes.py packs it into. */
final class Vectors {
    static final class Vector {
        final String pronto;
        final byte[] code;

        Vector(String pronto, byte[] code) {
            this.pronto = pronto;
            this.code = code;
        }
    }

    private Vectors() {}

    static Map<String, Vector> load() throws IOException {
        Map<String, Vector> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Paths.get("..", "tools", "testdata", "codec_vectors.txt"))) {
            String[] f = line.split("\t");
            out.put(f[0], new Vector(f[1], IrCodec.unhex(f[2])));
        }
        return out;
    }
}
