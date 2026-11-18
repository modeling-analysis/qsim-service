/*
 * qsim-service — a JMT-backed queueing-network simulation service.
 * Copyright (C) 2026 qsim-service contributors.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package qsim.build;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Build-time patch of JMT's {@code simEngine.Simulation} so that a finite capacity region may
 * contain fork-joins (spec: docs/superpowers/specs/2026-11-18-finite-capacity-regions-design.md,
 * section 6.1).
 *
 * <p>Stock {@code initialize()} insists that every region member has a {@code Storage} input and a
 * {@code Linkage} output. A Fork output or a Join input fails that test even though the region
 * bookkeeping those sections would host is not needed inside a region. The six instructions that
 * test and then use these sections are redirected to {@code qsim.engine.FcrShim}. Stack effects
 * are identical, so the original stack-map frames stay valid and the class is written without
 * recomputing them.
 *
 * <p>This is deliberately brittle: the jar hash, the class hash and the per-kind rewrite counts must all match, so a JMT
 * upgrade fails the build loudly instead of producing a silently different engine. It uses only
 * ASM and bytes, never JMT classes, and is excluded from the shipped jar.
 */
public final class JmtFcrPatcher {

  /** SHA-256 of {@code simEngine/Simulation.class} in the bundled JMT 1.5.0 jar. */
  public static final String EXPECTED_SHA256 =
      "c8ff675c8c5b8ce05afbd011e70343b8effc96d9adea0d9374caef2b07c80343";

  /** SHA-256 of the whole bundled {@code lib/JMT-singlejar-1.5.0.jar}. */
  public static final String EXPECTED_JAR_SHA256 =
      "4135177ee35663f0d7a2d8a021e390c587223c0987b728c91b5e66cd79d82e3d";

  static final String CLASS_ENTRY = "simEngine/Simulation.class";
  static final String MARKER_FIELD = "QSIM_FCR_PATCHED";
  private static final String SPEC = "docs/superpowers/specs/2026-11-18-finite-capacity-regions-design.md";
  private static final String SHIM = "qsim/engine/FcrShim";
  private static final String STORAGE = "NodeSections/Storage";
  private static final String LINKAGE = "NodeSections/Linkage";
  private static final String REGION = "QueueNet/BlockingRegion";
  /** Per kind: one Storage and one Linkage each. */
  private static final int EXPECTED_PER_KIND = 2;

  /** The rewritten class and how many instructions of each kind were rewritten. */
  public record Result(byte[] bytes, int instanceOfs, int checkcastDrops, int invokes) {
    public int rewrites() {
      return instanceOfs + checkcastDrops + invokes;
    }
  }

  private JmtFcrPatcher() {}

  /** Applies the rewrites and adds the marker field, without any hash check. */
  public static Result rewrite(byte[] classBytes) {
    ClassReader reader = new ClassReader(classBytes);
    // Flags 0: keep the original frames. Nothing here changes the stack at a merge point.
    ClassWriter writer = new ClassWriter(reader, 0);
    int[] count = {0, 0, 0}; // instanceof, checkcast drops, invokevirtual
    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
      @Override
      public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
        MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
        if (!name.equals("initialize") || !desc.equals("()V")) {
          return mv;
        }
        return new MethodVisitor(Opcodes.ASM9, mv) {
          @Override
          public void visitTypeInsn(int opcode, String type) {
            boolean storage = type.equals(STORAGE);
            boolean linkage = type.equals(LINKAGE);
            if (opcode == Opcodes.INSTANCEOF && (storage || linkage)) {
              count[0]++;
              super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM,
                  storage ? "storageOrJoin" : "linkageOrFork", "(Ljava/lang/Object;)Z", false);
            } else if (opcode == Opcodes.CHECKCAST && (storage || linkage)) {
              count[1]++; // dropped: the shim takes Object
            } else {
              super.visitTypeInsn(opcode, type);
            }
          }

          @Override
          public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals(STORAGE) && name.equals("redirectionTurnON")) {
              count[2]++;
              super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, "redirect",
                  "(Ljava/lang/Object;L" + REGION + ";)V", false);
            } else if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals(LINKAGE)
                && name.equals("borderRouterTurnON")) {
              count[2]++;
              super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, "border",
                  "(Ljava/lang/Object;L" + REGION + ";)V", false);
            } else {
              super.visitMethodInsn(opcode, owner, name, desc, itf);
            }
          }
        };
      }

      @Override
      public void visitEnd() {
        // Lets FcrPatchCheck tell the patched class from the stock one at startup.
        super.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            MARKER_FIELD, "Z", null, 1).visitEnd();
        super.visitEnd();
      }
    }, 0);
    return new Result(writer.toByteArray(), count[0], count[1], count[2]);
  }

  /** Checks the class hash, rewrites, and requires exactly two rewrites of each kind. */
  public static byte[] patch(byte[] classBytes) {
    String actual = sha256(classBytes);
    if (!EXPECTED_SHA256.equals(actual)) {
      throw new IllegalStateException("simEngine.Simulation SHA-256 is " + actual + ", expected "
          + EXPECTED_SHA256 + ": the bundled JMT changed. Re-verify the patch against " + SPEC);
    }
    Result r = rewrite(classBytes);
    requireCounts(r);
    return r.bytes();
  }

  /** Fails unless each rewrite kind happened exactly twice (once for Storage, once for Linkage). */
  static void requireCounts(Result r) {
    if (r.instanceOfs() != EXPECTED_PER_KIND || r.checkcastDrops() != EXPECTED_PER_KIND
        || r.invokes() != EXPECTED_PER_KIND) {
      throw new IllegalStateException("Rewrites in simEngine.Simulation.initialize() were "
          + r.instanceOfs() + " instanceof, " + r.checkcastDrops() + " checkcast drops, "
          + r.invokes() + " invokevirtual; expected " + EXPECTED_PER_KIND + " of each. See " + SPEC);
    }
  }

  /** Fails unless the file is exactly the bundled JMT 1.5.0 jar. */
  static void verifyJar(Path jar) throws IOException {
    String actual = sha256(Files.readAllBytes(jar));
    if (!EXPECTED_JAR_SHA256.equals(actual)) {
      throw new IllegalStateException(jar + " SHA-256 is " + actual + ", expected "
          + EXPECTED_JAR_SHA256 + ": the bundled JMT jar changed. Re-verify the patch against " + SPEC);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Args: {@code jarPath outputDir}. Writes {@code outputDir/simEngine/Simulation.class}. */
  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      throw new IllegalArgumentException("usage: JmtFcrPatcher <jmt-jar> <output-dir>");
    }
    verifyJar(Path.of(args[0]));
    byte[] original;
    try (ZipFile zip = new ZipFile(args[0]); InputStream in = zip.getInputStream(zip.getEntry(CLASS_ENTRY))) {
      original = in.readAllBytes();
    }
    byte[] patched = patch(original);
    Path out = Path.of(args[1], CLASS_ENTRY);
    Files.createDirectories(out.getParent());
    Files.write(out, patched);
  }
}
