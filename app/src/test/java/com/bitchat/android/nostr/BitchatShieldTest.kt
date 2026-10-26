package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BitchatShieldTest {

    @Before
    fun setUp() {
        BitchatShield.resetStateForTesting()
    }

    @Test
    fun `rejected multiline event does not reserve its link domain`() {
        val rejected = BitchatShield.inspectEvent("synthetic-a", (1..26).joinToString("\n") { "line" } + " https://example.org/first", "Reader", 1000L)
        assertTrue(rejected is BitchatShield.InspectionResult.Blocked)
        val next = BitchatShield.inspectEvent("synthetic-b", "Reference https://example.org/second", "Reader", 1001L)
        assertTrue(next is BitchatShield.InspectionResult.Allowed)
    }

    @Test
    fun `userinfo and explicit ports share the same domain admission slot`() {
        val links = listOf("https://example.org/a", "https://example.org:443/b", "https://alias@example.org/c", "https://www.example.org:8443/d")
        assertEquals(List(4) { "example.org" }, BitchatShield.extractNormalizedLinkKeys(links.joinToString(" ")))
        links.forEachIndexed { index, link ->
            val result = BitchatShield.inspectEvent("synthetic-$index", link, "Reader", 1000L + index)
            assertEquals(index == 0, result is BitchatShield.InspectionResult.Allowed)
        }
    }

    @Test
    fun `obfuscated domain and unicode hostname remain normalized`() {
        assertEquals(listOf("example.org", "example.org", "xn--bcher-kva.example"), BitchatShield.extractNormalizedLinkKeys("example[.]org example(.)org https://bücher.example/read"))
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
    fun `allows normal link once but blocks repeated link spam across different messages`() {
        val firstMsgWithLink = "Coucou tu veux réaliser des edits DECOUVRE https://autoeditlab.com jugé meilleur site"
        val secondMsgWithSameLink = "Salut t'as filmé tes bagarres DECOUVRE https://autoeditlab.com montage auto"

        val firstResult = BitchatShield.inspectEvent(
            pubkey = "aaaa1111bbbb2222",
            rawContent = firstMsgWithLink,
            rawNickname = "user1",
            createdAtSeconds = 1_000L
        )
        assertTrue(firstResult is BitchatShield.InspectionResult.Allowed)

        val secondResult = BitchatShield.inspectEvent(
            pubkey = "cccc3333dddd4444",
            rawContent = secondMsgWithSameLink,
            rawNickname = "user2",
            createdAtSeconds = 1_015L
        )
        assertTrue(secondResult is BitchatShield.InspectionResult.Blocked)
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
