package com.talacore.talatv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The grown-up gate's question (v0.14.0) — pure logic. The gate
 * is a PLAIN check, not a lock against a determined adult: hold
 * the button AND answer a question a small child can't be
 * expected to answer but any grown-up can. Numbers carry it
 * (they need no language): "which number is biggest?" and "what
 * is 7 + 5?". The gate keeps a child from wandering out of the
 * story room or sending a share by accident; it was never meant
 * to keep a grown-up out of anything.
 */
public final class GateQuestions {

    public static final int KIND_BIGGEST = 0;
    public static final int KIND_SUM = 1;

    public static final class Question {
        public int kind;
        /** For KIND_SUM: the two addends. For KIND_BIGGEST: unused. */
        public int a, b;
        public final List<Integer> options = new ArrayList<>();
        public int correctIndex = -1;
    }

    private GateQuestions() {}

    public static Question generate(Random rnd) {
        Question q = new Question();
        if (rnd.nextBoolean()) {
            q.kind = KIND_BIGGEST;
            int big = 12 + rnd.nextInt(80);        // 12..91
            int s1 = 1 + rnd.nextInt(big - 1);
            int s2 = 1 + rnd.nextInt(big - 1);
            if (s2 == s1) s2 = s2 + 1 < big ? s2 + 1 : s2 - 1;
            q.options.add(s1);
            q.options.add(big);
            q.options.add(s2);
            Collections.shuffle(q.options, rnd);
            q.correctIndex = q.options.indexOf(big);
        } else {
            q.kind = KIND_SUM;
            q.a = 6 + rnd.nextInt(13);             // 6..18
            q.b = 5 + rnd.nextInt(12);             // 5..16
            int sum = q.a + q.b;
            int d1 = sum + (rnd.nextBoolean() ? 2 : -2);
            int d2 = sum + (rnd.nextBoolean() ? 10 : -10);
            if (d2 <= 0 || d2 == d1) d2 = sum + 3;
            q.options.add(sum);
            q.options.add(d1);
            q.options.add(d2);
            Collections.shuffle(q.options, rnd);
            q.correctIndex = q.options.indexOf(sum);
        }
        return q;
    }

    public static boolean isCorrect(Question q, int chosenIndex) {
        return q != null && chosenIndex == q.correctIndex
                && chosenIndex >= 0;
    }
}
