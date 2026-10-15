package com.referboard.analyzer.letter;

/** STANDARD: a full one-page letter. SHORT: a few sentences for an email body or a referral form's note field. */
public enum LetterLength {
    STANDARD(3500, 450),
    SHORT(1100, 140);

    private final int maxChars;
    private final int maxWords;

    LetterLength(int maxChars, int maxWords) {
        this.maxChars = maxChars;
        this.maxWords = maxWords;
    }

    public int maxChars() {
        return maxChars;
    }

    public int maxWords() {
        return maxWords;
    }
}
