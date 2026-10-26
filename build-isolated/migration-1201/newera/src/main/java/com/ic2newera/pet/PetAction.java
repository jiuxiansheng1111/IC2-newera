package com.ic2newera.pet;

public enum PetAction {
    IDLE, PET, JUMP, NOD, SHAKE, STANDOFF, POUNCE, SWIPE, RETREAT, FLATTEN, RESTORE,
    BARK, DANCE, TILT, PAW, STRETCH, ROLL, BOW, YAWN, EAT;
    public String translationKey() { return "pet.ic2newera.action." + name().toLowerCase(java.util.Locale.ROOT); }
    public boolean isInteraction() {
        return switch (this) {
            case PET, JUMP, NOD, SHAKE, BARK, DANCE, TILT, PAW, STRETCH, ROLL, BOW, YAWN, EAT -> true;
            default -> false;
        };
    }
    public boolean isPlayAction() { return this == STANDOFF || this == POUNCE || this == SWIPE || this == RETREAT; }
}
