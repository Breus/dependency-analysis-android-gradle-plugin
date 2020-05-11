package com.autonomousapps

import com.autonomousapps.fixtures.ProjectDirProvider
import com.autonomousapps.internal.android.AgpVersion
import org.apache.commons.io.FileUtils
import org.gradle.util.GradleVersion
import spock.lang.Specification

abstract class AbstractFunctionalSpec extends Specification {

  private static final SUPPORTED_GRADLE_VERSIONS = [
    GradleVersion.version('6.1.1'),
    GradleVersion.version('6.2.2'),
    GradleVersion.version('6.3'),
    GradleVersion.version('6.4')
  ]

  protected static Boolean quick() {
    return System.getProperty("com.autonomousapps.quick").toBoolean()
  }

  protected static void clean(ProjectDirProvider projectDirProvider) {
    clean(projectDirProvider.projectDir)
  }

  protected static void clean(File rootDir) {
    FileUtils.deleteDirectory(rootDir)
  }

  protected static boolean isCompatible(GradleVersion gradleVersion, AgpVersion agpVersion) {
    if (agpVersion >= AgpVersion.version('4.1.0')) {
      return gradleVersion >= GradleVersion.version('6.4')
    } else {
      return true
    }
  }

  /**
   * Testing against AGP versions:
   * - 3.5.3
   * - 3.6.3
   * - 4.0.0, whose min Gradle version is 6.1
   * - 4.1.0, whose min Gradle version is 6.4
   */
  protected static List<GradleVersion> gradleVersions(String agpVersion = '') {
    AgpVersion agp
    if (agpVersion.isEmpty()) {
      agp = AgpVersion.AGP_MIN
    } else {
      agp = AgpVersion.version(agpVersion)
    }

    def gradleVersions = SUPPORTED_GRADLE_VERSIONS
    gradleVersions.removeAll {
      !isCompatible(it, agp)
    }
    return gradleVersions
  }

  /**
   * For example, given:
   * 1. [GradleVersion(5.6.4), GradleVersion(6.0.1), GradleVersion(6.1.1)] (size=3)
   * 2. [true, false] (size=2)
   * ...
   * n. [another, list, of, items]
   *
   * I want to return:
   * [[GradleVersion(5.6.4), true], [GradleVersion(5.6.4), false], [GradleVersion(6.0.1), true], [GradleVersion(6.0.1), false], [GradleVersion(6.1.1), true], [GradleVersion(6.1.1), false]]
   *
   * @param pipes an iterable of pipes
   * @return a list of lists
   */
  protected static multivariableDataPipe(List<Object>... pipes) {
    return Arrays.asList(pipes).combinations()
  }
}
