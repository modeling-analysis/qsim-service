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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class JmtFcrPatcherTest {

  private static final String JAR = "lib/JMT-singlejar-1.5.0.jar";

  private static byte[] fromJar(String entry) throws Exception {
    try (ZipFile zip = new ZipFile(JAR); InputStream in = zip.getInputStream(zip.getEntry(entry))) {
      return in.readAllBytes();
    }
  }

  @Test
  void patchesTheBundledClassWithExactlySixRewrites() throws Exception {
    byte[] original = fromJar(JmtFcrPatcher.CLASS_ENTRY);
    assertEquals(6, JmtFcrPatcher.rewrite(original).rewrites());
    assertDoesNotThrow(() -> JmtFcrPatcher.patch(original));
  }

  @Test
  void eachRewriteKindHappensTwice() throws Exception {
    JmtFcrPatcher.Result r = JmtFcrPatcher.rewrite(fromJar(JmtFcrPatcher.CLASS_ENTRY));
    assertEquals(2, r.instanceOfs());
    assertEquals(2, r.checkcastDrops());
    assertEquals(2, r.invokes());
  }

  @Test
  void aWrongCountForAnyOneKindFailsEvenWhenTheTotalIsSix() {
    var e = assertThrows(IllegalStateException.class,
        () -> JmtFcrPatcher.requireCounts(new JmtFcrPatcher.Result(new byte[0], 3, 2, 1)));
    assertTrue(e.getMessage().contains("2026-11-18-finite-capacity-regions-design.md"), e.getMessage());
    assertDoesNotThrow(() -> JmtFcrPatcher.requireCounts(new JmtFcrPatcher.Result(new byte[0], 2, 2, 2)));
  }

  @Test
  void pinsTheWholeJar() throws Exception {
    assertDoesNotThrow(() -> JmtFcrPatcher.verifyJar(java.nio.file.Path.of(JAR)));
    IllegalStateException e = assertThrows(IllegalStateException.class,
        () -> JmtFcrPatcher.verifyJar(java.nio.file.Path.of("pom.xml")));
    assertTrue(e.getMessage().contains("2026-11-18-finite-capacity-regions-design.md"), e.getMessage());
  }

  @Test
  void refusesAClassWithADifferentHash() throws Exception {
    IllegalStateException e = assertThrows(IllegalStateException.class,
        () -> JmtFcrPatcher.patch(fromJar("simEngine/SimLoader.class")));
    assertTrue(e.getMessage().contains("SHA-256"), e.getMessage());
    assertTrue(e.getMessage().contains("2026-11-18-finite-capacity-regions-design.md"), e.getMessage());
  }

  @Test
  void refusesWhenTheRewriteCountIsWrong() throws Exception {
    assertEquals(0, JmtFcrPatcher.rewrite(fromJar("simEngine/SimLoader.class")).rewrites());
  }

  @Test
  void patchedClassCarriesTheMarker() throws Exception {
    byte[] patched = JmtFcrPatcher.patch(fromJar(JmtFcrPatcher.CLASS_ENTRY));
    ClassLoader parent = getClass().getClassLoader();
    ClassLoader loader = new ClassLoader(parent) {
      @Override
      protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (name.equals("simEngine.Simulation")) {
          return defineClass(name, patched, 0, patched.length);
        }
        throw new ClassNotFoundException(name);
      }

      @Override
      protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.equals("simEngine.Simulation")) {
          return findClass(name);
        }
        return super.loadClass(name, resolve);
      }
    };
    Class<?> sim = loader.loadClass("simEngine.Simulation");
    assertTrue(sim.getField("QSIM_FCR_PATCHED").getBoolean(null));
  }

  @Test
  void patchedInitializeCallsTheShim() throws Exception {
    byte[] patched = JmtFcrPatcher.patch(fromJar(JmtFcrPatcher.CLASS_ENTRY));
    List<String> calls = new ArrayList<>();
    List<String> casts = new ArrayList<>();
    new ClassReader(patched).accept(new ClassVisitor(Opcodes.ASM9) {
      @Override
      public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
        if (!name.equals("initialize") || !desc.equals("()V")) {
          return null;
        }
        return new MethodVisitor(Opcodes.ASM9) {
          @Override
          public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
            if (op == Opcodes.INVOKESTATIC) {
              calls.add(owner + "." + n);
            }
          }

          @Override
          public void visitTypeInsn(int op, String type) {
            if (op == Opcodes.CHECKCAST) {
              casts.add(type);
            }
          }
        };
      }
    }, 0);
    for (String m : List.of("storageOrJoin", "redirect", "linkageOrFork", "border")) {
      assertEquals(1, calls.stream().filter(("qsim/engine/FcrShim." + m)::equals).count(), m);
    }
    assertTrue(!casts.contains("NodeSections/Storage") && !casts.contains("NodeSections/Linkage"), casts.toString());
  }
}
