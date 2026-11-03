package com.saas.org.lunch;

import com.saas.org.lunch.LunchGrouper.Pair;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How a round's participants are split (REQ-LUNCH-03): sizes, leftovers, randomness and the avoidance of recent pairs. */
@Tag("REQ-LUNCH-03")
class LunchGrouperTest {

    private final LunchGrouper grouper = new LunchGrouper(200);

    private static List<UUID> people(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> new UUID(0, i)).toList();
    }

    private static List<Integer> sizes(List<List<UUID>> groups) {
        return groups.stream().map(List::size).sorted(java.util.Comparator.reverseOrder()).toList();
    }

    // ---- the shape ----

    @ParameterizedTest(name = "{0} people in groups of {1} -> {2}")
    @CsvSource(delimiter = '|', value = {
            "2|2|2", "3|2|3", "4|2|2 2", "5|2|3 2", "6|2|2 2 2", "7|2|3 2 2",
            "2|3|2", "3|3|3", "4|3|2 2", "5|3|3 2", "6|3|3 3", "7|3|3 2 2", "8|3|3 3 2", "10|3|3 3 2 2",
            "2|4|2", "3|4|3", "4|4|4", "5|4|3 2", "6|4|4 2", "7|4|4 3", "8|4|4 4", "9|4|4 3 2", "13|4|4 4 3 2"})
    void groupsHaveTheConfiguredSizeAndTheLeftoverIsPlacedSoNoGroupIsBelowTwo(int people, int size, String expected) {
        List<Integer> expectedSizes = java.util.Arrays.stream(expected.split(" ")).map(Integer::parseInt).toList();

        assertThat(LunchGrouper.shape(people, size)).isEqualTo(expectedSizes);
    }

    @Test
    void noShapeEverHasAGroupSmallerThanTwoOrLargerThanFourAndAlwaysAddsUpToEveryone() {
        for (int size = 2; size <= 4; size++) {
            for (int people = 2; people <= 60; people++) {
                List<Integer> shape = LunchGrouper.shape(people, size);

                assertThat(shape).as(people + " in groups of " + size).allMatch(group -> group >= 2 && group <= 4);
                assertThat(shape.stream().mapToInt(Integer::intValue).sum()).isEqualTo(people);
            }
        }
    }

    @Test
    void aLoneLeftoverJoinsAGroupOnlyWhenGroupsOfTwoLeaveNoOtherWay() {
        assertThat(LunchGrouper.shape(5, 2)).as("2+2+1 cannot stand").containsExactly(3, 2);
        assertThat(LunchGrouper.shape(9, 4)).as("4+4+1 is rebalanced instead of making 5").containsExactly(4, 3, 2);
    }

    @Test
    void fewerPeopleThanOneGroupMakeOneGroupOfThemAll() {
        assertThat(LunchGrouper.shape(3, 4)).containsExactly(3);
        assertThat(LunchGrouper.shape(2, 4)).containsExactly(2);
    }

    @Test
    void aSingleParticipantOrAnImpossibleSizeIsRefused() {
        assertThatThrownBy(() -> LunchGrouper.shape(1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LunchGrouper.shape(0, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LunchGrouper.shape(5, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LunchGrouper.shape(5, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LunchGrouper(0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- who ends up where ----

    @ParameterizedTest(name = "{0} people")
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 11, 17, 30})
    void everyParticipantIsInExactlyOneGroupWhateverTheSizeAndTheSeed(int people) {
        for (int size = 2; size <= 4; size++) {
            for (long seed = 0; seed < 5; seed++) {
                List<List<UUID>> groups = grouper.group(people(people), size, Set.of(), new Random(seed));

                List<UUID> flat = groups.stream().flatMap(List::stream).toList();
                assertThat(flat).as(people + "/" + size + "/" + seed).containsExactlyInAnyOrderElementsOf(people(people)).doesNotHaveDuplicates();
                assertThat(sizes(groups)).isEqualTo(LunchGrouper.shape(people, size));
                assertThat(groups).allSatisfy(group -> assertThat(group).isSorted());
            }
        }
    }

    @Test
    void theSameSeedGivesTheSameGroupsWhateverOrderTheParticipantsComeIn() {
        List<UUID> forward = people(12);
        List<UUID> backward = new ArrayList<>(forward);
        java.util.Collections.reverse(backward);

        assertThat(grouper.group(forward, 3, Set.of(), new Random(42))).isEqualTo(grouper.group(backward, 3, Set.of(), new Random(42)));
    }

    @Test
    void differentSeedsGiveDifferentGroupsSoItReallyIsRandom() {
        Set<List<List<UUID>>> outcomes = new HashSet<>();
        for (long seed = 0; seed < 20; seed++) {
            outcomes.add(grouper.group(people(8), 2, Set.of(), new Random(seed)));
        }

        assertThat(outcomes.size()).isGreaterThan(5);
    }

    @Test
    void everyoneCanMeetEveryoneOverManyRoundsSoNoPairIsImpossible() {
        Set<Pair> seen = new HashSet<>();
        for (long seed = 0; seed < 300; seed++) {
            grouper.group(people(6), 2, Set.of(), new Random(seed)).forEach(group -> seen.add(Pair.of(group.get(0), group.get(1))));
        }

        assertThat(seen).hasSize(15); // 6 over 2
    }

    @Test
    void aDuplicatedParticipantCountsOnce() {
        List<UUID> twice = new ArrayList<>(people(4));
        twice.add(people(4).get(0));

        List<List<UUID>> groups = grouper.group(twice, 2, Set.of(), new Random(1));

        assertThat(groups.stream().flatMap(List::stream).toList()).hasSize(4);
    }

    // ---- avoiding recent pairs ----

    @Test
    void pairsFromRecentRoundsAreAvoidedWhenThereIsAWayToAvoidThem() {
        List<UUID> four = people(4);
        Set<Pair> recent = Set.of(Pair.of(four.get(0), four.get(1)), Pair.of(four.get(2), four.get(3)));

        for (long seed = 0; seed < 30; seed++) {
            List<List<UUID>> groups = grouper.group(four, 2, recent, new Random(seed));

            assertThat(LunchGrouper.repeatedPairs(groups, recent)).as("seed " + seed).isZero();
        }
    }

    @Test
    void withALargerGroupWhoeverCanBeKeptApartIs() {
        List<UUID> people = people(9);
        Set<Pair> recent = new HashSet<>();
        for (int i = 0; i < 9; i += 3) { // the three trios of last time
            recent.add(Pair.of(people.get(i), people.get(i + 1)));
            recent.add(Pair.of(people.get(i), people.get(i + 2)));
            recent.add(Pair.of(people.get(i + 1), people.get(i + 2)));
        }

        for (long seed = 0; seed < 20; seed++) {
            assertThat(LunchGrouper.repeatedPairs(grouper.group(people, 3, recent, new Random(seed)), recent)).as("seed " + seed).isZero();
        }
    }

    @Test
    void whenAnyAssignmentRepeatsAPairTheFewestRepeatsAreChosen() {
        List<UUID> three = people(3); // one group of three: every pair is in it
        Set<Pair> recent = Set.of(Pair.of(three.get(0), three.get(1)));

        List<List<UUID>> groups = grouper.group(three, 2, recent, new Random(7));

        assertThat(groups).hasSize(1);
        assertThat(LunchGrouper.repeatedPairs(groups, recent)).as("unavoidable, and still a valid group").isEqualTo(1);
    }

    @Test
    void theBestOfTheTriedAssignmentsIsKeptNotTheLast() {
        List<UUID> people = people(10);
        Set<Pair> recent = new HashSet<>();
        for (int i = 0; i < 10; i += 2) {
            recent.add(Pair.of(people.get(i), people.get(i + 1)));
        }

        int worstSingleTry = LunchGrouper.repeatedPairs(new LunchGrouper(1).group(people, 2, recent, new Random(3)), recent);
        int best = LunchGrouper.repeatedPairs(new LunchGrouper(500).group(people, 2, recent, new Random(3)), recent);

        assertThat(best).isLessThanOrEqualTo(worstSingleTry).isZero();
    }

    @Test
    void pairsThatDidNotMeetRecentlyAreNotAvoidedNeedlesslyAndAPairIsOrderFree() {
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);

        assertThat(Pair.of(a, b)).isEqualTo(Pair.of(b, a));
        assertThat(LunchGrouper.repeatedPairs(List.of(List.of(a, b)), Set.of(Pair.of(b, a)))).isEqualTo(1);
        assertThat(LunchGrouper.repeatedPairs(List.of(List.of(a, b)), Set.of())).isZero();
    }

}
