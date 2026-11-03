package com.saas.org.lunch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Splits the participants of a lunch round into groups (REQ-LUNCH-03). Pure: it takes the participants, the configured
 * size, the pairs that have lunched together recently and a {@link Random}, and returns the groups - so the same seed gives
 * the same groups and the rules can be tested without a database.
 *
 * <p><b>Shape.</b> As many full groups of the configured size as there are, then the rest: nothing left over is fine; two or
 * more left over become one smaller group; exactly one left over would be alone, so it is never left alone - with a size
 * of 3 or 4 a member is taken from the last full group to make a group of two ({@code 3+3+1} becomes {@code 3+2+2}), and
 * with a size of 2 the single member joins a group, making one of three. Fewer participants than one group make one group of
 * them all. So no group is ever smaller than 2 and none is larger than 4.
 *
 * <p><b>Who goes where.</b> Random, but pairs that were in a group together in the recent rounds are avoided as far as
 * that is possible: a number of random assignments is tried and the one with the fewest repeated pairs is kept (it stops at
 * the first with none). It is a best effort, not a guarantee - with few participants every assignment may repeat a pair.
 */
public final class LunchGrouper {

    /** Two members, order-free. */
    public record Pair(UUID first, UUID second) {

        public static Pair of(UUID a, UUID b) {
            return a.compareTo(b) <= 0 ? new Pair(a, b) : new Pair(b, a);
        }
    }

    public static final int MIN_GROUP = 2;
    public static final int MAX_GROUP = 4;

    private final int attempts;

    public LunchGrouper(int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("at least one attempt is needed");
        }
        this.attempts = attempts;
    }

    /** The sizes of the groups for {@code participants} people and a configured size, largest first. */
    public static List<Integer> shape(int participants, int groupSize) {
        if (participants < MIN_GROUP) {
            throw new IllegalArgumentException("a group needs at least " + MIN_GROUP + " participants");
        }
        if (groupSize < MIN_GROUP || groupSize > MAX_GROUP) {
            throw new IllegalArgumentException("the group size must be between " + MIN_GROUP + " and " + MAX_GROUP);
        }
        if (participants <= groupSize) {
            return List.of(participants);
        }
        int full = participants / groupSize;
        int rest = participants % groupSize;
        List<Integer> sizes = new ArrayList<>(Collections.nCopies(full, groupSize));
        if (rest >= MIN_GROUP) {
            sizes.add(rest);
        } else if (rest == 1) {
            if (groupSize >= 3) {
                sizes.set(full - 1, groupSize - 1);
                sizes.add(MIN_GROUP);
            } else {
                sizes.set(0, groupSize + 1);
            }
        }
        sizes.sort(Collections.reverseOrder());
        return sizes;
    }

    /** @return the groups, each with its members in id order; every participant appears in exactly one */
    public List<List<UUID>> group(Collection<UUID> participants, int groupSize, Set<Pair> recentPairs, Random random) {
        Set<UUID> distinct = new HashSet<>(participants);
        List<Integer> sizes = shape(distinct.size(), groupSize);
        List<UUID> sorted = new ArrayList<>(distinct);
        Collections.sort(sorted); // the database's order must not decide what a seed produces

        List<List<UUID>> best = null;
        int bestRepeats = Integer.MAX_VALUE;
        int tries = recentPairs.isEmpty() ? 1 : attempts;
        for (int attempt = 0; attempt < tries && bestRepeats > 0; attempt++) {
            List<UUID> shuffled = new ArrayList<>(sorted);
            Collections.shuffle(shuffled, random);
            List<List<UUID>> candidate = split(shuffled, sizes);
            int repeats = repeatedPairs(candidate, recentPairs);
            if (repeats < bestRepeats) {
                best = candidate;
                bestRepeats = repeats;
            }
        }
        List<List<UUID>> result = new ArrayList<>();
        for (List<UUID> group : best) {
            List<UUID> ordered = new ArrayList<>(group);
            Collections.sort(ordered);
            result.add(ordered);
        }
        return result;
    }

    /** How many of the pairs that sit together in {@code groups} are in {@code recentPairs}. */
    public static int repeatedPairs(List<List<UUID>> groups, Set<Pair> recentPairs) {
        int repeats = 0;
        for (List<UUID> group : groups) {
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    if (recentPairs.contains(Pair.of(group.get(i), group.get(j)))) {
                        repeats++;
                    }
                }
            }
        }
        return repeats;
    }

    private static List<List<UUID>> split(List<UUID> shuffled, List<Integer> sizes) {
        List<List<UUID>> groups = new ArrayList<>();
        int from = 0;
        for (int size : sizes) {
            groups.add(new ArrayList<>(shuffled.subList(from, from + size)));
            from += size;
        }
        return groups;
    }
}
