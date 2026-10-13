package com.referboard.analyzer.scoring;

import java.util.List;

/** Exposes the job-title core-word logic to other packages. */
public final class AtsCheckerAccess {

    private AtsCheckerAccess() {
    }

    public static List<String> titleWords(String title) {
        return AtsChecker.titleWords(title);
    }
}
