/*
 * Copyright 2016 DiffPlug
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.diffplug.spotless;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/** COMPLETELY DEPRECATED, use {@link PaddedCell#canonicalIfDirty(Formatter, File)} instead. */
@Deprecated
public final class PaddedCellBulk {
	private static final Logger logger = Logger.getLogger(PaddedCellBulk.class.getName());

	/**
	 * Returns true if the formatter is misbehaving for any of the given files.
	 *
	 * If, after 500ms of searching, none are found that misbehave, it gives the
	 * formatter the benefit of the doubt and returns false. The real point of this
	 * check is to handle the case that a user just called spotlessApply, but spotlessCheck
	 * is still failing.  In that case, all of the problemFiles are guaranteed to
	 * be misbehaving, so this time limit doesn't hurt correctness.
	 *
	 * If you call this method after every failed spotlessCheck, it can help you
	 * tell the user about a misbehaving rule and alert her to how to enable
	 * paddedCell mode, with minimal effort.
	 */
	@Deprecated
	public static boolean anyMisbehave(Formatter formatter, List<File> problemFiles) {
		return anyMisbehave(formatter, problemFiles, 500);
	}

	/** Same as {@link #anyMisbehave(Formatter, List)} but with a customizable timeout. */
	@Deprecated
	public static boolean anyMisbehave(Formatter formatter, List<File> problemFiles, long timeoutMs) {
		Objects.requireNonNull(formatter, "formatter");
		Objects.requireNonNull(problemFiles, "problemFiles");
		long start = System.currentTimeMillis();
		for (File problem : problemFiles) {
			PaddedCell padded = PaddedCell.check(formatter, problem);
			if (padded.misbehaved()) {
				return true;
			}
			if (timeoutMs > 0 && System.currentTimeMillis() - start > timeoutMs) {
				return false;
			}
		}
		return false;
	}

	/**
	 * Performs a full check using PaddedCell logic on the given files with the given formatter.
	 * If any are found which do not conform to the PaddedCell, a description of the error will
	 * be written to the diagnose dir.
	 *
	 * @param rootDir		The root directory, used to determine the relative paths of the problemFiles.
	 * @param diagnoseDir	Directory where problems are described (based on the relative paths determined based on rootDir).
	 * @param formatter		The formatter to apply.
	 * @param problemFiles	The files with which we have a problem.
	 * @return	A list of files which are failing because of paddedCell problems, but could be fixed. (specifically, the files for which spotlessApply would be effective)
	 */
	@SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
	@Deprecated
	public static List<File> check(File rootDir, File diagnoseDir, Formatter formatter, List<File> problemFiles) throws IOException {
		Objects.requireNonNull(rootDir, "rootDir");
		Objects.requireNonNull(diagnoseDir, "diagnoseDir");
		Objects.requireNonNull(formatter, "formatter");
		Objects.requireNonNull(problemFiles, "problemFiles");

		// "fake" Formatter which can use the already-computed result of a PaddedCell as
		FakeStep paddedCellStep = new FakeStep();
		try (Formatter paddedFormatter = Formatter.builder()
				.lineEndingsPolicy(formatter.getLineEndingsPolicy())
				.encoding(formatter.getEncoding())
				.rootDir(formatter.getRootDir())
				.steps(Collections.singletonList(paddedCellStep))
				.exceptionPolicy(formatter.getExceptionPolicy())
				.build()) {

			// empty out the diagnose folder
			Path rootPath = rootDir.toPath();
			Path diagnosePath = diagnoseDir.toPath();
			cleanDir(diagnosePath);

			List<File> stillFailing = new ArrayList<>();
			for (File problemFile : problemFiles) {
				logger.fine("Running padded cell check on " + problemFile);
				PaddedCell padded = PaddedCell.check(formatter, problemFile);
				if (!padded.misbehaved()) {
					logger.fine("    well-behaved.");
				} else {
					// the file is misbehaved, so we'll write all its steps to DIAGNOSE_DIR
					Path relative = rootPath.relativize(problemFile.toPath());
					Path diagnoseFile = diagnosePath.resolve(relative);
					for (int i = 0; i < padded.steps().size(); ++i) {
						Path path = Paths.get(diagnoseFile + "." + padded.type().name().toLowerCase(Locale.ROOT) + i);
						Files.createDirectories(path.getParent());
						String version = padded.steps().get(i);
						Files.write(path, version.getBytes(formatter.getEncoding()));
					}
					// dump the type of the misbehavior to console
					logger.fine("    " + relative + " " + padded.userMessage());
				}

				if (!padded.isResolvable()) {
					// if it's not resolvable, then there's
					// no point killing the build over it
				} else {
					// if the input is resolvable, we'll use that to try again at
					// determining if it's clean
					paddedCellStep.set(problemFile, padded.canonical());
					if (!paddedFormatter.isClean(problemFile)) {
						stillFailing.add(problemFile);
					}
				}
			}
			return stillFailing;
		}
	}

	/** Helper for check(). */
	@SuppressWarnings("serial")
	@SuppressFBWarnings("SE_NO_SERIALVERSIONID")
	static class FakeStep implements FormatterStep {
		private File file;
		private String formatted;

		void set(File file, String formatted) {
			this.file = file;
			this.formatted = formatted;
		}

		@Override
		public String format(String raw, File file) throws Exception {
			if (file.equals(this.file)) {
				this.file = null;
				return Objects.requireNonNull(formatted);
			} else {
				throw new IllegalArgumentException("Must call set() before each call to format.");
			}
		}

		@Override
		public String getName() {
			return "Padded cell result";
		}
	}

	/** Performs the typical spotlessApply, but with PaddedCell handling of misbehaving FormatterSteps. */
	@Deprecated
	public static void apply(Formatter formatter, File file) throws IOException {
		applyAnyChanged(formatter, file);
	}

	/** Performs the typical spotlessApply, but with PaddedCell handling of misbehaving FormatterSteps. */
	@Deprecated
	public static boolean applyAnyChanged(Formatter formatter, File file) throws IOException {
		PaddedCell.DirtyState dirtyState = PaddedCell.calculateDirtyState(formatter, file);
		if (dirtyState.isClean() || dirtyState.didNotConverge()) {
			return false;
		} else {
			dirtyState.writeCanonicalTo(file);
			return true;
		}
	}

	/** Does whatever it takes to turn this path into an empty folder. */
	private static void cleanDir(Path folder) throws IOException {
		if (Files.exists(folder)) {
			if (Files.isDirectory(folder)) {
				Files.walkFileTree(folder, FOLDER_CLEANING_VISITOR);
			} else {
				Files.delete(folder);
			}
		}
		Files.createDirectories(folder);
	}

	private static final SimpleFileVisitor<Path> FOLDER_CLEANING_VISITOR = new SimpleFileVisitor<Path>() {
		@Override
		public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
			Files.delete(file);
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult visitFileFailed(final Path file, final IOException e) {
			return handleException(e);
		}

		private FileVisitResult handleException(final IOException e) {
			logger.log(Level.SEVERE, e.getMessage(), e);
			return FileVisitResult.TERMINATE;
		}

		@Override
		public FileVisitResult postVisitDirectory(final Path dir, final IOException e)
				throws IOException {
			if (e != null) {
				return handleException(e);
			}
			Files.delete(dir);
			return FileVisitResult.CONTINUE;
		}
	};
}
