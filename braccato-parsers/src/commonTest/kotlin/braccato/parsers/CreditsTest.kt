// Port of packages/parsers/src/__tests__/credits.test.ts.
package braccato.parsers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SplitCreditNamesTest {
    @Test fun `splits on slashes and commas`() = assertEquals(
        listOf("Max Martin", "Oscar Holter", "Abel Tesfaye"),
        splitCreditNames("Max Martin / Oscar Holter, Abel Tesfaye"),
    )

    @Test fun `splits on the CJK list separators`() =
        assertEquals(listOf("周杰伦", "方文山", "林夕"), splitCreditNames("周杰伦、方文山，林夕"))

    @Test fun `returns a single name untouched apart from trimming`() =
        assertEquals(listOf("Freddie Mercury"), splitCreditNames("  Freddie Mercury "))

    @Test fun `returns nothing for empty or whitespace input`() {
        assertEquals(emptyList(), splitCreditNames(""))
        assertEquals(emptyList(), splitCreditNames("   "))
        assertEquals(emptyList(), splitCreditNames(" / , "))
    }

    @Test fun `keeps an ampersand inside a name`() =
        assertEquals(listOf("Simon & Garfunkel"), splitCreditNames("Simon & Garfunkel"))
}

class UniqueNamesTest {
    @Test fun `drops exact duplicates and keeps the first-seen order`() =
        assertEquals(listOf("B", "A", "C"), uniqueNames(listOf("B", "A", "B", "C", "A")))

    @Test fun `trims and drops empty names`() = assertEquals(listOf("A"), uniqueNames(listOf(" A ", "", "  ", "A")))

    @Test fun `returns nothing for no names`() = assertEquals(emptyList(), uniqueNames(emptyList()))

    @Test fun `treats names that differ in case as different people`() =
        assertEquals(listOf("Max Martin", "max martin"), uniqueNames(listOf("Max Martin", "max martin")))

    @Test fun `leaves its input untouched`() {
        val input = mutableListOf(" A ", "A")
        uniqueNames(input)
        assertEquals(listOf(" A ", "A"), input)
    }

    @Test fun `is idempotent`() {
        val once = uniqueNames(listOf("B", "A", "B"))
        assertEquals(once, uniqueNames(once))
    }
}

class IsCreditRoleTest {
    @Test fun `recognises CJK and English roles whatever their spacing and case`() {
        assertTrue(isCreditRole("作词"))
        assertTrue(isCreditRole(" Produced  By "))
    }

    @Test fun `recognises an unlisted CJK role by the noun it ends in`() = assertTrue(isCreditRole("填词"))

    @Test fun `reads the ending noun only on a role-length prefix`() = assertFalse(isCreditRole("我听见你的声音"))

    @Test fun `reads a role with a Latin prefix or several roles joined by a separator`() {
        assertTrue(isCreditRole("Rap作词"))
        assertTrue(isCreditRole("作曲/编曲"))
        assertTrue(isCreditRole("制作人&混音"))
        assertTrue(isCreditRole("作詞・作曲"))
        assertTrue(isCreditRole("作词·作曲"))
    }

    @Test fun `does not read a joined singer list as a role`() {
        assertFalse(isCreditRole("Travis Scott/The Notorious B.I.G."))
        assertFalse(isCreditRole("周杰伦/方文山"))
    }

    @Test fun `does not read a singer as a role`() {
        assertFalse(isCreditRole("Drake"))
        assertFalse(isCreditRole("王力宏"))
    }
}

class IsCreditLineTest {
    @Test fun `recognises a credit of any role`() {
        assertTrue(isCreditLine("编曲：钟兴民"))
        assertTrue(isCreditLine(" Written by: Max Martin "))
    }

    @Test fun `rejects a lyric that only holds a colon`() = assertFalse(isCreditLine("Listen: I wrote this"))

    @Test fun `rejects a role with no names after it`() = assertFalse(isCreditLine("作词："))

    @Test fun `rejects empty text`() = assertFalse(isCreditLine(""))
}

class SongwritersInCreditLineTest {
    @Test fun `lists the names a songwriting credit gives`() {
        assertEquals(listOf("周杰伦", "方文山"), songwritersInCreditLine("作曲 : 周杰伦/方文山"))
        assertEquals(listOf("Sia Furler"), songwritersInCreditLine("Lyrics by: Sia Furler"))
    }

    @Test fun `reads a songwriting role inside a compound or prefixed role`() {
        assertEquals(listOf("张三"), songwritersInCreditLine("Rap作词：张三"))
        assertEquals(listOf("李四"), songwritersInCreditLine("作曲/编曲：李四"))
        assertEquals(listOf("甲", "乙"), songwritersInCreditLine("作詞・作曲：甲/乙"))
        assertEquals(listOf("王五"), songwritersInCreditLine("词曲：王五"))
        assertEquals(listOf("赵六"), songwritersInCreditLine("作词作曲：赵六"))
    }

    @Test fun `lists nothing for a credit that is not songwriting`() {
        assertEquals(emptyList(), songwritersInCreditLine("编曲：钟兴民"))
        assertEquals(emptyList(), songwritersInCreditLine("Produced by: Someone"))
    }

    @Test fun `lists nothing for a sung line or empty text`() {
        assertEquals(emptyList(), songwritersInCreditLine("Drake: yeah"))
        assertEquals(emptyList(), songwritersInCreditLine(""))
    }
}
