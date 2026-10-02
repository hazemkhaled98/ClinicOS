package com.clinicos.identity.api;

/**
 * Single source of truth for the clinic role ladder:
 * owner &gt; manager &gt; doctor &gt; assistant = receptionist.
 *
 * <p>Ranking expresses <em>ordering</em> only. It does not by itself grant
 * account administration (changing a password, suspending, reactivating,
 * assigning a role, or editing a role's permissions). Those additionally
 * require the actor to be an account administrator — see
 * {@link #canAdministerAccounts(String)}.
 */
public final class RoleHierarchy {

    public static final String OWNER = "owner";
    public static final String MANAGER = "manager";
    public static final String DOCTOR = "doctor";

    private RoleHierarchy() {
    }

    /**
     * @return the ladder position, higher being more senior
     * @throws IllegalArgumentException if the role code is not a known role, so a
     *         newly seeded role cannot silently inherit an unintended rank
     */
    public static int rankOf(String roleCode) {
        return switch (roleCode) {
            case OWNER -> 4;
            case MANAGER -> 3;
            case DOCTOR -> 2;
            case "assistant", "receptionist" -> 1;
            default -> throw new IllegalArgumentException("دور غير معروف: " + roleCode);
        };
    }

    /**
     * Only the owner and a manager may administer accounts. A doctor outranks
     * assistant and receptionist on the ladder but must not gain account
     * administration over them.
     */
    public static boolean canAdministerAccounts(String actorRole) {
        return OWNER.equals(actorRole) || MANAGER.equals(actorRole);
    }

    /**
     * @return whether the actor may administer accounts belonging to the target role
     */
    public static boolean canAdminister(String actorRole, String targetRole) {
        return canAdministerAccounts(actorRole) && rankOf(actorRole) > rankOf(targetRole);
    }
}
