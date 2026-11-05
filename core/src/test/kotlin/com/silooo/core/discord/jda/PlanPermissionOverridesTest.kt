package com.silooo.core.discord.jda

import net.dv8tion.jda.api.Permission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlanPermissionOverridesTest {
    @Test
    fun `private read only channels deny visibility and writing in the same override`() {
        assertEquals(
            setOf(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_SEND_IN_THREADS, Permission.CREATE_PUBLIC_THREADS),
            planPublicDenials(isPrivate = true, readOnly = true, locked = false),
        )
    }

    @Test
    fun `locked private voice combines visibility and connection denials`() {
        assertEquals(setOf(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT), planPublicDenials(isPrivate = true, readOnly = false, locked = true))
        assertEquals(emptySet<Permission>(), planPublicDenials(isPrivate = false, readOnly = false, locked = false))
    }
}
