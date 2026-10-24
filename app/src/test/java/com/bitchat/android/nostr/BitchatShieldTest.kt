package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BitchatShieldTest {

    @Before
    fun setUp() {
        BitchatShield.resetStateForTesting()
    }

    @Test
    fun `blocks exact baguette camembert zalgo attack from user prompt`() {
        val zalgoAttack = "baguette#̢̛ͥ̀̽̽ͨ̌̓͆̇̀̽ͦͨͫͯ̏̎̇̈̚͜҉̕͢͏͡҉̸̶̛͢͡҉̵̨̭̳̘̹̫͔͖̜̠͖̫̲̖̱̤̻̖͉͢͞͡ͅͅ͏̸̷̢͑̊̀̐ͣͭͭ̉͂ͨ̽͒̊̎͛ͪ̊̎ͩͧ̀͝͠͠͏̸̵̵̷̵#̢̛ͥ̀̽̽ͨ̌̓͆̇̀̽ͦͨͫͯ̏̎̇̈̚͜҉̕͢͏͡҉̸̶̛͢͡҉̵̨̭̳̘̹̫͔͖̜̠͖̫̲̖̱̤̻̖͉͢͞͡ͅͅ͏̸̷̢͑̊̀̐ͣͭͭ̉͂ͨ̽͒̊̎͛ͪ̊̎ͩͧ camembert#̢̛ͥ̀̽̽ͨ̌̓͆̇̀̽ͦͨͫͯ̏̎̇̈̚͜҉̕͢͏͡҉̸̶̛͢͡҉̵̨̭̳̘̹̫͔͖̜̠͖̫̲̖̱̤̻̖͉͢͞͡ͅͅ͏̸̷̢͑̊̀̐ͣͭͭ̉͂ͨ̽͒̊̎͛ͪ̊̎ͩͧ"
        assertTrue(BitchatShield.containsZalgoOrForbiddenChars(zalgoAttack))

        val result = BitchatShield.inspectEvent(
            pubkey = "deadbeef12345678",
            rawContent = zalgoAttack,
            rawNickname = "baguette#̢̛ͥ̀̽̽ͨ̌"
        )
        assertTrue(result is BitchatShield.InspectionResult.Blocked)
    }

    @Test
    fun `allows normal French messages with accents and emojis`() {
        val normalFrench = "Salut, ça va ? Rendez-vous à côté de la gare d'Orléans cet été ! ❤️😂🔥"
        assertFalse(BitchatShield.containsZalgoOrForbiddenChars(normalFrench))
        assertFalse(BitchatShield.containsLinkOrUrl(normalFrench))

        val result = BitchatShield.inspectEvent(
            pubkey = "1122334455667788",
            rawContent = normalFrench,
            rawNickname = "Théo"
        )
        assertTrue(result is BitchatShield.InspectionResult.Allowed)
        val allowed = result as BitchatShield.InspectionResult.Allowed
        assertEquals(normalFrench, allowed.cleanContent)
        assertEquals("Théo", allowed.cleanNickname)
    }

    @Test
    fun `blocks link spam and promotional URLs`() {
        val linkSpam = "Coucou tu veux réaliser des edits DECOUVRE https://autoeditlab.com jugé meilleur site"
        assertTrue(BitchatShield.containsLinkOrUrl(linkSpam))

        val blockedUrl = BitchatShield.inspectEvent(
            pubkey = "aaaa1111bbbb2222",
            rawContent = linkSpam,
            rawNickname = "user1"
        )
        assertTrue(blockedUrl is BitchatShield.InspectionResult.Blocked)

        val blockedDomain = BitchatShield.inspectEvent(
            pubkey = "aaaa1111bbbb3333",
            rawContent = "Rejoins sur autoeditlab.com ou discord.gg/test",
            rawNickname = "user2"
        )
        assertTrue(blockedDomain is BitchatShield.InspectionResult.Blocked)
    }

    @Test
    fun `blocks bot hex nicknames and cross-pubkey duplicate long spam`() {
        val botResult = BitchatShield.inspectEvent(
            pubkey = "aaaa1111bbbb2222",
            rawContent = "Bonjour",
            rawNickname = "liquid-bounce_c958b26e"
        )
        assertTrue(botResult is BitchatShield.InspectionResult.Blocked)

        val longSpamWithoutLink = "Coucou tu veux realiser des edits de tes bagarres durant les manifs meilleur montage de toute l annee"
        val first = BitchatShield.inspectEvent(
            pubkey = "pubkey_one_111111",
            rawContent = longSpamWithoutLink,
            rawNickname = "alice",
            createdAtSeconds = 1_000L
        )
        assertTrue(first is BitchatShield.InspectionResult.Allowed)

        val duplicateFromRotatedKey = BitchatShield.inspectEvent(
            pubkey = "pubkey_two_222222",
            rawContent = longSpamWithoutLink,
            rawNickname = "bob",
            createdAtSeconds = 1_005L
        )
        assertTrue(duplicateFromRotatedKey is BitchatShield.InspectionResult.Blocked)
    }
}
