/*******************************************************************************
 * Copyright (c) 2009, 2024 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Marc R. Hoffmann - initial API and implementation
 *
 *******************************************************************************/
package org.jacoco.core.internal.flow;

import java.util.List;
import java.util.Objects;

import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.internal.instr.InstrSupport;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.AnalyzerAdapter;

/**
 * A {@link org.objectweb.asm.ClassVisitor} that calculates probes for every
 * method.
 */
public class ClassProbesAdapter extends ClassVisitor
		implements IProbeIdGenerator {

	private static final MethodProbesVisitor EMPTY_METHOD_PROBES_VISITOR = new MethodProbesVisitor() {
	};

	private final ClassProbesVisitor cv;

	private final boolean trackFrames;

	private int counter = 0;

	private String name;

	private String className;

	/**
	 * Creates a new adapter that delegates to the given visitor.
	 *
	 * @param cv
	 *            instance to delegate to
	 * @param trackFrames
	 *            if <code>true</code> stackmap frames are tracked and provided
	 */
	public ClassProbesAdapter(final ClassProbesVisitor cv,
			final boolean trackFrames) {
		super(InstrSupport.ASM_API_VERSION, cv);
		this.cv = cv;
		this.trackFrames = trackFrames;
	}

	public ClassProbesAdapter(final ClassProbesVisitor cv,
			final boolean trackFrames, String className) {
		super(InstrSupport.ASM_API_VERSION, cv);
		this.cv = cv;
		this.trackFrames = trackFrames;
		this.className = className;

	}

	@Override
	public void visit(final int version, final int access, final String name,
			final String signature, final String superName,
			final String[] interfaces) {
		this.name = name;
		super.visit(version, access, name, signature, superName, interfaces);
	}

	@Override
	public final MethodVisitor visitMethod(final int access, final String name,
			final String desc, final String signature,
			final String[] exceptions) {
		final MethodProbesVisitor methodProbes;
		final MethodProbesVisitor mv = cv.visitMethod(access, name, desc,
				signature, exceptions);
		boolean isInRule = true;
		if (!CoverageBuilder.methodNames.isEmpty()) {
			isInRule = CoverageBuilder.methodNames.stream().anyMatch(line -> {
				String ruleClassName = line.split(":")[0];

				if (!ruleClassName.contains(className)) {
					return false;
				}
				if (Objects.equals(line.split(":")[1], "true")) {
					return true;
				}
				List<String> ruleMethodNameAndParamList = List
						.of(line.split(":")[1].split("#"));
				return ruleMethodNameAndParamList.stream()
						.anyMatch(ruleMethodNameAndParam -> {
							String[] split = ruleMethodNameAndParam.split(",");

							boolean methodNameMatch = name.equals(split[0]);
							if (!methodNameMatch) {
								return false;
							}
							int hitCount = 0;
							for (int i = 1; i < split.length; i++) {
								if (desc.contains(split[i])) {
									hitCount++;
								}
							}
							if (hitCount == split.length - 1) {
								return true;
							}
							return false;

						}

						);

			});
		}

		if (mv != null && isInRule) {
			methodProbes = mv;
		} else {
			// We need to visit the method in any case, otherwise probe ids
			// are not reproducible
			methodProbes = EMPTY_METHOD_PROBES_VISITOR;
		}
		return new MethodSanitizer(null, access, name, desc, signature,
				exceptions) {

			@Override
			public void visitEnd() {
				super.visitEnd();
				LabelFlowAnalyzer.markLabels(this);
				final MethodProbesAdapter probesAdapter = new MethodProbesAdapter(
						methodProbes, ClassProbesAdapter.this);
				if (trackFrames) {
					final AnalyzerAdapter analyzer = new AnalyzerAdapter(
							ClassProbesAdapter.this.name, access, name, desc,
							probesAdapter);
					probesAdapter.setAnalyzer(analyzer);
					methodProbes.accept(this, analyzer);
				} else {
					methodProbes.accept(this, probesAdapter);
				}
			}
		};
	}

	@Override
	public void visitEnd() {
		cv.visitTotalProbeCount(counter);
		super.visitEnd();
	}

	// === IProbeIdGenerator ===

	public int nextId() {
		return counter++;
	}

}
