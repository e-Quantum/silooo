package com.silooo.core.discord.jda

import net.dv8tion.jda.api.Permission
import com.silooo.core.discord.ManagedRolePermission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class PlanPermissionOverridesTest {
    @Test
    fun `role updates add only required native permissions and preserve existing ones`() {
        val existing = Permission.CREATE_INSTANT_INVITE.rawValue or (1L shl 60)
        val result = mergedRolePermissions(existing, setOf(ManagedRolePermission.MODERATE_MEMBERS))
        assertEquals(existing or Permission.MODERATE_MEMBERS.rawValue, result)
        assertFalse(result and Permission.ADMINISTRATOR.rawValue != 0L)
    }

    @Test
    fun `a public channel preserves manual send locks and unrelated permissions`() {
        val masks = planPublicPermissionMasks(
            Permission.MESSAGE_MANAGE.rawValue or Permission.VIEW_CHANNEL.rawValue,
            Permission.MESSAGE_SEND.rawValue or Permission.MANAGE_WEBHOOKS.rawValue,
            isPrivate = false, readOnly = false, locked = false,
        )
        assertEquals(Permission.MESSAGE_MANAGE.rawValue or Permission.VIEW_CHANNEL.rawValue, masks.first)
        assertEquals(Permission.MANAGE_WEBHOOKS.rawValue or Permission.MESSAGE_SEND.rawValue, masks.second)
        assertEquals(masks, planPublicPermissionMasks(masks.first, masks.second, false, false, false))
    }

    @Test
    fun `private channels remain hidden and read only after reconciliation`() {
        val masks = planPublicPermissionMasks(Permission.VIEW_CHANNEL.rawValue, 0L, true, true, false)
        assertEquals(0L, masks.first)
        assertEquals(
            Permission.VIEW_CHANNEL.rawValue or Permission.MESSAGE_SEND.rawValue or
                Permission.MESSAGE_SEND_IN_THREADS.rawValue or Permission.CREATE_PUBLIC_THREADS.rawValue,
            masks.second,
        )
    }

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
