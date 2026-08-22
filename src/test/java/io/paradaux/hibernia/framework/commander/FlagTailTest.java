package io.paradaux.hibernia.framework.commander;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlagTailTest {

    private static final FlagSpec PAGE =
            new FlagSpec("page", List.of("p"), Integer.class, false, true, "1");
    private static final FlagSpec PLAYER =
            new FlagSpec("player", List.of(), String.class, false, true, "");
    private static final FlagSpec NOW =
            new FlagSpec("now", List.of(), boolean.class, true, true, "");

    private static final List<FlagSpec> FLAGS = List.of(PAGE, PLAYER, NOW);

    // ── tokenizing ────────────────────────────────────────────────────────────────

    @Test
    void tokenize_splitsOnWhitespaceAndRecordsOffsets() {
        List<FlagTail.Token> tokens = FlagTail.tokenize("--page 2");

        assertEquals(2, tokens.size());
        assertEquals("--page", tokens.get(0).text());
        assertEquals(0, tokens.get(0).start());
        assertEquals("2", tokens.get(1).text());
        assertEquals(7, tokens.get(1).start());
    }

    @Test
    void tokenize_keepsQuotedValueIntact() {
        List<FlagTail.Token> tokens = FlagTail.tokenize("--player \"Notch The Great\"");

        assertEquals(2, tokens.size());
        assertEquals("Notch The Great", tokens.get(1).text());
        assertTrue(tokens.get(1).quoted());
    }

    @Test
    void tokenize_treatsUnterminatedQuoteAsRunningToEnd() {
        // Mid-typing this is the normal state; failing here would break completion.
        List<FlagTail.Token> tokens = FlagTail.tokenize("--player \"Notch The");

        assertEquals(2, tokens.size());
        assertEquals("Notch The", tokens.get(1).text());
    }

    @Test
    void tokenize_emptyInputYieldsNoTokens() {
        assertTrue(FlagTail.tokenize("").isEmpty());
        assertTrue(FlagTail.tokenize("    ").isEmpty());
    }

    // ── parsing ───────────────────────────────────────────────────────────────────

    @Test
    void parse_blankTailYieldsNoValues() {
        assertTrue(FlagTail.parse(null, FLAGS).isEmpty());
        assertTrue(FlagTail.parse("   ", FLAGS).isEmpty());
    }

    @Test
    void parse_readsSpaceSeparatedValue() {
        assertEquals(Map.of("page", "2"), FlagTail.parse("--page 2", FLAGS));
    }

    @Test
    void parse_readsEqualsSeparatedValue() {
        assertEquals(Map.of("page", "2"), FlagTail.parse("--page=2", FLAGS));
    }

    @Test
    void parse_acceptsFlagsInAnyOrder() {
        Map<String, String> first = FlagTail.parse("--page 2 --player Notch", FLAGS);
        Map<String, String> second = FlagTail.parse("--player Notch --page 2", FLAGS);

        assertEquals(first, second);
        assertEquals("2", first.get("page"));
        assertEquals("Notch", first.get("player"));
    }

    @Test
    void parse_resolvesAliasToCanonicalName() {
        assertEquals(Map.of("page", "3"), FlagTail.parse("--p 3", FLAGS));
    }

    @Test
    void parse_presenceFlagNeedsNoValue() {
        Map<String, String> values = FlagTail.parse("--now", FLAGS);

        assertTrue(values.containsKey("now"));
    }

    @Test
    void parse_presenceFlagBeforeValueFlagDoesNotSwallowIt() {
        Map<String, String> values = FlagTail.parse("--now --page 4", FLAGS);

        assertTrue(values.containsKey("now"));
        assertEquals("4", values.get("page"));
    }

    @Test
    void parse_repeatedFlagTakesLastValue() {
        assertEquals("9", FlagTail.parse("--page 2 --page 9", FLAGS).get("page"));
    }

    @Test
    void parse_negativeNumberIsAValueNotAFlag() {
        FlagSpec offset = new FlagSpec("offset", List.of(), Integer.class, false, true, "");
        Map<String, String> values = FlagTail.parse("--offset -5", List.of(offset));

        assertEquals("-5", values.get("offset"));
    }

    @Test
    void parse_quotedValueThatLooksLikeAFlagIsAValue() {
        Map<String, String> values = FlagTail.parse("--player \"--notaflag\"", FLAGS);

        assertEquals("--notaflag", values.get("player"));
    }

    @Test
    void parse_rejectsUnknownFlag() {
        FlagTail.FlagSyntaxException thrown = assertThrows(FlagTail.FlagSyntaxException.class,
                () -> FlagTail.parse("--nope 1", FLAGS));

        assertTrue(thrown.getMessage().contains("--nope"));
    }

    @Test
    void parse_rejectsValueFlagWithNoValue() {
        assertThrows(FlagTail.FlagSyntaxException.class, () -> FlagTail.parse("--page", FLAGS));
        assertThrows(FlagTail.FlagSyntaxException.class, () -> FlagTail.parse("--page --now", FLAGS));
        assertThrows(FlagTail.FlagSyntaxException.class, () -> FlagTail.parse("--page=", FLAGS));
    }

    @Test
    void parse_rejectsValueGivenToPresenceFlag() {
        assertThrows(FlagTail.FlagSyntaxException.class, () -> FlagTail.parse("--now=yes", FLAGS));
    }

    @Test
    void parse_rejectsBareWordWhereAFlagWasExpected() {
        FlagTail.FlagSyntaxException thrown = assertThrows(FlagTail.FlagSyntaxException.class,
                () -> FlagTail.parse("stray", FLAGS));

        assertTrue(thrown.getMessage().contains("stray"));
    }

    // ── completion ────────────────────────────────────────────────────────────────

    @Test
    void completion_emptyTailOffersFlagNames() {
        FlagTail.Completion completion = FlagTail.completionAt("", FLAGS);

        assertEquals(FlagTail.CompletionKind.FLAG_NAME, completion.kind());
        assertEquals("", completion.prefix());
        assertEquals(0, completion.start());
        assertTrue(completion.usedNames().isEmpty());
    }

    @Test
    void completion_partialFlagNameAnchorsAtTokenStart() {
        FlagTail.Completion completion = FlagTail.completionAt("--pa", FLAGS);

        assertEquals(FlagTail.CompletionKind.FLAG_NAME, completion.kind());
        assertEquals("--pa", completion.prefix());
        assertEquals(0, completion.start());
    }

    @Test
    void completion_afterValueFlagNameOffersItsValue() {
        FlagTail.Completion completion = FlagTail.completionAt("--page ", FLAGS);

        assertEquals(FlagTail.CompletionKind.FLAG_VALUE, completion.kind());
        assertSame(PAGE, completion.flag());
        assertEquals("", completion.prefix());
        assertEquals(7, completion.start());
    }

    @Test
    void completion_partialValueAnchorsAtValueStart() {
        FlagTail.Completion completion = FlagTail.completionAt("--player No", FLAGS);

        assertEquals(FlagTail.CompletionKind.FLAG_VALUE, completion.kind());
        assertSame(PLAYER, completion.flag());
        assertEquals("No", completion.prefix());
        assertEquals(9, completion.start());
    }

    @Test
    void completion_insideEqualsFormAnchorsAfterTheEquals() {
        FlagTail.Completion completion = FlagTail.completionAt("--player=No", FLAGS);

        assertEquals(FlagTail.CompletionKind.FLAG_VALUE, completion.kind());
        assertSame(PLAYER, completion.flag());
        assertEquals("No", completion.prefix());
        assertEquals(9, completion.start());
    }

    @Test
    void completion_alreadyUsedFlagsAreReported() {
        FlagTail.Completion completion = FlagTail.completionAt("--page 2 ", FLAGS);

        assertEquals(FlagTail.CompletionKind.FLAG_NAME, completion.kind());
        assertTrue(completion.usedNames().contains("page"));
        assertFalse(completion.usedNames().contains("player"));
    }

    @Test
    void completion_flagAwaitingItsValueIsNotCountedAsUsed() {
        // Otherwise the value suggester would filter out the very flag being completed.
        FlagTail.Completion completion = FlagTail.completionAt("--page ", FLAGS);

        assertFalse(completion.usedNames().contains("page"));
    }

    @Test
    void completion_afterPresenceFlagOffersMoreFlagNames() {
        FlagTail.Completion completion = FlagTail.completionAt("--now ", FLAGS);

        assertEquals(FlagTail.CompletionKind.FLAG_NAME, completion.kind());
        assertNull(completion.flag());
        assertTrue(completion.usedNames().contains("now"));
    }

    @Test
    void completion_unknownFlagFallsBackToNameSuggestions() {
        FlagTail.Completion completion = FlagTail.completionAt("--bogus ", FLAGS);

        assertEquals(FlagTail.CompletionKind.FLAG_NAME, completion.kind());
    }

    @Test
    void completion_nullTailBehavesAsEmpty() {
        assertEquals(FlagTail.CompletionKind.FLAG_NAME, FlagTail.completionAt(null, FLAGS).kind());
    }

    // ── FlagSpec ──────────────────────────────────────────────────────────────────

    @Test
    void flagSpec_allNamesListsCanonicalNameFirst() {
        assertEquals(List.of("page", "p"), PAGE.allNames());
        assertEquals(List.of("player"), PLAYER.allNames());
    }

    @Test
    void flagSpec_answersToCanonicalNameAndAliases() {
        assertTrue(PAGE.answersTo("page"));
        assertTrue(PAGE.answersTo("p"));
        assertFalse(PAGE.answersTo("pages"));
        assertFalse(PLAYER.answersTo("p"));
    }
}
