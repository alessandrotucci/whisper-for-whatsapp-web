package io.github.alessandrotucci.whisperwa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Confronto tra due sequenze di parole con lo stesso algoritmo di difflib.SequenceMatcher di
 * Python, senza elementi spazzatura (autojunk=False): blocchi comuni, operazioni e somiglianza
 * coincidono con quelli calcolati dal componente desktop, così le parole segnate come corrette
 * sono le stesse nell'app e nell'estensione.
 */
final class Difflib {

    /** Un'operazione di get_opcodes: tag è "equal", "replace", "delete" o "insert". */
    static final class Opcode {
        final String tag;
        final int i1, i2, j1, j2;

        Opcode(String tag, int i1, int i2, int j1, int j2) {
            this.tag = tag;
            this.i1 = i1;
            this.i2 = i2;
            this.j1 = j1;
            this.j2 = j2;
        }
    }

    private final List<String> a;
    private final List<String> b;
    private final Map<String, List<Integer>> b2j = new HashMap<>();
    private List<int[]> blocks;

    Difflib(List<String> a, List<String> b) {
        this.a = a;
        this.b = b;
        for (int j = 0; j < b.size(); j++) {
            b2j.computeIfAbsent(b.get(j), k -> new ArrayList<>()).add(j);
        }
    }

    private int[] longestMatch(int alo, int ahi, int blo, int bhi) {
        int besti = alo, bestj = blo, bestsize = 0;
        Map<Integer, Integer> j2len = new HashMap<>();
        for (int i = alo; i < ahi; i++) {
            Map<Integer, Integer> newj2len = new HashMap<>();
            List<Integer> js = b2j.get(a.get(i));
            if (js != null) {
                for (int j : js) {
                    if (j < blo) continue;
                    if (j >= bhi) break;
                    Integer prev = j2len.get(j - 1);
                    int k = (prev == null ? 0 : prev) + 1;
                    newj2len.put(j, k);
                    if (k > bestsize) {
                        besti = i - k + 1;
                        bestj = j - k + 1;
                        bestsize = k;
                    }
                }
            }
            j2len = newj2len;
        }
        while (besti > alo && bestj > blo && a.get(besti - 1).equals(b.get(bestj - 1))) {
            besti--;
            bestj--;
            bestsize++;
        }
        while (besti + bestsize < ahi && bestj + bestsize < bhi
                && a.get(besti + bestsize).equals(b.get(bestj + bestsize))) {
            bestsize++;
        }
        return new int[]{besti, bestj, bestsize};
    }

    /** Blocchi comuni (i, j, n) in ordine, chiusi dal blocco sentinella (len a, len b, 0). */
    List<int[]> matchingBlocks() {
        if (blocks != null) return blocks;
        int la = a.size(), lb = b.size();
        Deque<int[]> queue = new ArrayDeque<>();
        queue.push(new int[]{0, la, 0, lb});
        List<int[]> found = new ArrayList<>();
        while (!queue.isEmpty()) {
            int[] q = queue.pop();
            int[] x = longestMatch(q[0], q[1], q[2], q[3]);
            int i = x[0], j = x[1], k = x[2];
            if (k > 0) {
                found.add(x);
                if (q[0] < i && q[2] < j) queue.push(new int[]{q[0], i, q[2], j});
                if (i + k < q[1] && j + k < q[3]) queue.push(new int[]{i + k, q[1], j + k, q[3]});
            }
        }
        found.sort((p, r) -> p[0] != r[0] ? Integer.compare(p[0], r[0])
                : p[1] != r[1] ? Integer.compare(p[1], r[1]) : Integer.compare(p[2], r[2]));
        List<int[]> out = new ArrayList<>();
        int i1 = 0, j1 = 0, k1 = 0;
        for (int[] m : found) {
            if (i1 + k1 == m[0] && j1 + k1 == m[1]) {
                k1 += m[2];
            } else {
                if (k1 > 0) out.add(new int[]{i1, j1, k1});
                i1 = m[0];
                j1 = m[1];
                k1 = m[2];
            }
        }
        if (k1 > 0) out.add(new int[]{i1, j1, k1});
        out.add(new int[]{la, lb, 0});
        blocks = out;
        return out;
    }

    List<Opcode> opcodes() {
        List<Opcode> out = new ArrayList<>();
        int i = 0, j = 0;
        for (int[] m : matchingBlocks()) {
            int ai = m[0], bj = m[1], size = m[2];
            String tag = null;
            if (i < ai && j < bj) tag = "replace";
            else if (i < ai) tag = "delete";
            else if (j < bj) tag = "insert";
            if (tag != null) out.add(new Opcode(tag, i, ai, j, bj));
            i = ai + size;
            j = bj + size;
            if (size > 0) out.add(new Opcode("equal", ai, i, bj, j));
        }
        return out;
    }

    double ratio() {
        int matches = 0;
        for (int[] m : matchingBlocks()) matches += m[2];
        int total = a.size() + b.size();
        return total == 0 ? 1.0 : 2.0 * matches / total;
    }
}
