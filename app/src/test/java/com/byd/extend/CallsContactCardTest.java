package com.byd.extend;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class CallsContactCardTest {
    private static final String CONTACT_CARD_ID =
            "com.byd.bluetoothcall:id/contacts_detail_dialog";

    @Test
    public void normalizesFieldsExtractedFromRedactedContactFixture() throws Exception {
        CallsRuntime.ContactCard card = readRedactedCard();

        assertEquals("Test contact", card.name);
        assertEquals(Arrays.asList("+12025550100", "+12025550101"), card.numbers);
    }

    @Test
    public void normalizesAndDeduplicatesNumbersAndRejectsInvalidFields() {
        CallsRuntime.ContactCard deduplicated = CallsRuntime.ContactCard.fromFields(
                Collections.singletonList(" Test contact "),
                Arrays.asList("+1 (202) 555-0100", " +1 202-555-0100 "));
        assertNotNull(deduplicated);
        assertEquals("Test contact", deduplicated.name);
        assertEquals(Collections.singletonList("+12025550100"), deduplicated.numbers);

        assertNull(CallsRuntime.ContactCard.fromFields(
                Collections.singletonList("Test contact"),
                Arrays.asList("+1 (202) 555-0100", "112x")));
        assertNull(CallsRuntime.ContactCard.fromFields(
                Arrays.asList("First", "Second"), Collections.singletonList("2025550100")));
        assertNull(CallsRuntime.ContactCard.fromFields(
                Collections.singletonList("  "), Collections.singletonList("2025550100")));
    }

    @Test
    public void acceptsShortNumbersFromContactCards() {
        CallsRuntime.ContactCard card = CallsRuntime.ContactCard.fromFields(
                Collections.singletonList("Short-code contact"),
                Arrays.asList("112", "1 1 2"));
        assertNotNull(card);
        assertEquals(Collections.singletonList("112"), card.numbers);
    }

    @Test
    public void freshCardComparisonAcceptsSameSelectionAndRejectsReplacement() throws Exception {
        CallsRuntime.ContactCard saved = readRedactedCard();
        CallsRuntime.ContactCard same = CallsRuntime.ContactCard.fromFields(
                Collections.singletonList(" Test contact "),
                Arrays.asList("+12025550100", "+1 (202) 555-0101"));
        CallsRuntime.ContactCard changedName = CallsRuntime.ContactCard.fromFields(
                Collections.singletonList("Different contact"), saved.numbers);
        CallsRuntime.ContactCard changedNumbers = CallsRuntime.ContactCard.fromFields(
                Collections.singletonList(saved.name),
                Arrays.asList("+12025550100", "+12025550102"));

        assertTrue(saved.sameAs(same));
        assertFalse(saved.sameAs(changedName));
        assertFalse(saved.sameAs(changedNumbers));
        assertFalse(saved.sameAs(null));
    }

    private static CallsRuntime.ContactCard readRedactedCard() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        Document document;
        try (InputStream input = CallsContactCardTest.class.getResourceAsStream(
                "/calls_contact_card_redacted.xml")) {
            assertNotNull(input);
            document = factory.newDocumentBuilder().parse(input);
        }

        NodeList cards = document.getElementsByTagName("node");
        Element card = null;
        int cardCount = 0;
        for (int index = 0; index < cards.getLength(); index++) {
            Element node = (Element) cards.item(index);
            if (!CONTACT_CARD_ID.equals(node.getAttribute("resource-id"))) continue;
            card = node;
            cardCount++;
        }
        assertEquals(1, cardCount);
        return CallsRuntime.ContactCard.fromFields(
                visibleTexts(card, "com.byd.bluetoothcall:id/contacts_detail_name"),
                visibleTexts(card, "com.byd.bluetoothcall:id/contacts_detail_number"));
    }

    private static List<String> visibleTexts(Element root, String resourceId) {
        List<String> values = new ArrayList<>();
        NodeList nodes = root.getElementsByTagName("node");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element node = (Element) nodes.item(index);
            if (resourceId.equals(node.getAttribute("resource-id"))
                    && Boolean.parseBoolean(node.getAttribute("visible-to-user"))) {
                String text = node.getAttribute("text");
                if (!text.isEmpty()) values.add(text);
            }
        }
        return values;
    }
}
