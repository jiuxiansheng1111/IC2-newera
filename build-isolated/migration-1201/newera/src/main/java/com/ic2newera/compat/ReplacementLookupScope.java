package com.ic2newera.compat;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-local reverse index scoped to one Mega Cells recipe-filter pass. */
public final class ReplacementLookupScope {
    private static final ThreadLocal<ArrayDeque<Scope>> SCOPES = new ThreadLocal<>();

    private ReplacementLookupScope() {}

    public static void begin() {
        ArrayDeque<Scope> stack = SCOPES.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            SCOPES.set(stack);
        }
        stack.push(new Scope());
    }

    public static void end() {
        ArrayDeque<Scope> stack = SCOPES.get();
        if (stack == null) return;
        stack.pop();
        if (stack.isEmpty()) SCOPES.remove();
    }

    /** Returns null outside a MEGA pass so OEI's original method can run unchanged. */
    public static Collection<String> lookup(ConcurrentHashMap<String, String> itemMap, String targetId) {
        ArrayDeque<Scope> stack = SCOPES.get();
        if (stack == null || stack.isEmpty()) return null;

        Scope scope = stack.peek();
        if (scope.reverseIndex == null) {
            Map<String, Set<String>> reverse = new HashMap<>();
            itemMap.forEach((sourceId, mappedId) ->
                    reverse.computeIfAbsent(mappedId, ignored -> new HashSet<>()).add(sourceId));
            scope.reverseIndex = reverse;
        }

        Set<String> sources = targetId == null ? null : scope.reverseIndex.get(targetId);
        // OEI returns a new, mutable HashSet even for no matches.
        return sources == null ? new HashSet<>() : new HashSet<>(sources);
    }

    private static final class Scope {
        private Map<String, Set<String>> reverseIndex;
    }
}
