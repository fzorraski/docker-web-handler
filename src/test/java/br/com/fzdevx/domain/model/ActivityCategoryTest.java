package br.com.fzdevx.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityCategoryTest {

    @Test
    void signingInIsAuthenticationNotWork() {
        assertEquals(ActivityCategory.AUTH, ActivityCategory.of("LOGIN"));
        assertEquals(ActivityCategory.AUTH, ActivityCategory.of("LOGIN_FAILED"));
        assertEquals(ActivityCategory.AUTH, ActivityCategory.of("LOGOUT"));
        assertFalse(ActivityCategory.AUTH.operational(),
                "the ranking must not reward whoever logs in most often");
    }

    @Test
    void changingYourOwnPasswordIsAuth_butResettingSomeoneElsesIsAdmin() {
        // both mention a password; only one of them is the account owner acting
        assertEquals(ActivityCategory.AUTH, ActivityCategory.of("PASSWORD_CHANGE"));
        assertEquals(ActivityCategory.ADMIN, ActivityCategory.of("USER_PASSWORD_RESET"));
    }

    @Test
    void knownPrefixesLandInTheirCategory() {
        assertEquals(ActivityCategory.CONTAINER, ActivityCategory.of("CONTAINER_UPGRADE"));
        assertEquals(ActivityCategory.CONTAINER, ActivityCategory.of("IMAGE_REMOVE"));
        assertEquals(ActivityCategory.CONTAINER, ActivityCategory.of("SCHEDULE_CREATE"));
        assertEquals(ActivityCategory.DATABASE, ActivityCategory.of("DATABASE_RESTORE"));
        assertEquals(ActivityCategory.DATABASE, ActivityCategory.of("DUMP_DELETE"));
        assertEquals(ActivityCategory.TERMINAL, ActivityCategory.of("TERMINAL_OPEN"));
        assertEquals(ActivityCategory.ADMIN, ActivityCategory.of("ROLE_UPDATE"));
        assertEquals(ActivityCategory.ADMIN, ActivityCategory.of("TENANT_CREATE"));
        assertEquals(ActivityCategory.ADMIN, ActivityCategory.of("SETTINGS_UPDATE"));
    }

    @Test
    void anUnknownActionIsKeptAsOtherNotDropped() {
        // a report that silently omits events is worse than one with an "other"
        // slice - a new action must still show up somewhere
        assertEquals(ActivityCategory.OTHER, ActivityCategory.of("SOMETHING_BRAND_NEW"));
        assertEquals(ActivityCategory.OTHER, ActivityCategory.of(null));
        assertEquals(ActivityCategory.OTHER, ActivityCategory.of("   "));
    }

    @Test
    void everyCategoryExceptAuthCounts() {
        for (ActivityCategory category : ActivityCategory.values()) {
            assertEquals(category != ActivityCategory.AUTH, category.operational(), category.name());
        }
    }

    @Test
    void failuresAreRecognisedBySuffixOrDenial() {
        assertTrue(ActivityCategory.failure("LOGIN_FAILED"));
        assertTrue(ActivityCategory.failure("START_FAILED"));
        assertTrue(ActivityCategory.failure("ACCESS_DENIED"));
        assertFalse(ActivityCategory.failure("LOGIN"));
        assertFalse(ActivityCategory.failure(null));
    }
}
