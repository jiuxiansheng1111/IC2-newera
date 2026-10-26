package com.ic2newera.industrial;

/** Small opt-in control contract; unsupported machines remain read-only. */
public interface ControllableMachine {
    boolean isAutomationPaused();

    void setAutomationPaused(boolean paused);

    /** Stable status code such as NONE, WAITING_INPUT, OUTPUT_FULL, NO_POWER, or ERROR. */
    String automationProblem();
}
