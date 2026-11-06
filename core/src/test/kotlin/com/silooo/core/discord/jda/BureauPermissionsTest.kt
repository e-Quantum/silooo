package com.silooo.core.discord.jda

import net.dv8tion.jda.api.Permission
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BureauPermissionsTest {
    @Test fun publicViewAndWriterChangesPreserveUnrelatedRights() {
        val view = Permission.VIEW_CHANNEL.rawValue
        val write = Permission.MESSAGE_SEND.rawValue
        val manage = Permission.MESSAGE_MANAGE.rawValue
        val history = Permission.MESSAGE_HISTORY.rawValue
        val public = bureauPermissionMasks(write or manage, view or history, view, write)
        assertEquals(view or manage, public.first)
        assertEquals(write or history, public.second)
        val formerMember = bureauPermissionMasks(view or write, manage, 0L, write)
        assertEquals(view, formerMember.first)
        assertEquals(write or manage, formerMember.second)
        val member = bureauPermissionMasks(formerMember.first, formerMember.second, write, 0L)
        assertEquals(view or write, member.first)
        assertEquals(manage, member.second)
        assertEquals(member, bureauPermissionMasks(member.first, member.second, write, 0L))
    }

    @Test fun onlyPlayerCardsAndPrivateReminderStopsAreRoutedOutsideGuild() {
        assertEquals(setOf("entreprise", "usine"), ACCOUNT_COMMANDS)
        assertTrue(isAccountButton("jeu-montrer:opaque"))
        assertTrue(isAccountButton("rappels-arreter:GEMMES"))
        assertFalse(isAccountButton("annonce-publier:opaque"))
        assertFalse(isAccountButton("delier-confirmer:opaque"))
    }
}
