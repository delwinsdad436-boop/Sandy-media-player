package com.talacore.talatv;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.InputStream;
import java.util.Map;

import javax.xml.parsers.SAXParserFactory;

/**
 * Streaming XMLTV reader (v0.11.0). SAX, never DOM — a full hosted
 * guide file is tens of megabytes and the phone never holds it.
 *
 * The join is by id and nothing else: the caller passes the guide
 * map's xml-id → Sandy tvg-id bridge for ONE source file, and only
 * programmes whose channel id is in that bridge are kept. A channel
 * whose id never appears contributes nothing — which is exactly how
 * a stale map row degrades to "no guide data" instead of a wrong
 * schedule: the file is the proof, the map only says where to look.
 */
public final class XmltvParser {

    private XmltvParser() {}

    private static final int MAX_DESC = 480;

    public static GuideModel.GuideData parse(InputStream in,
                                             Map<String, String> xmlIdToTvgId)
            throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        // Hosted XMLTV is plain data; no DTD processing wanted.
        try { factory.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                false); } catch (Exception ignored) { }
        XMLReader reader = factory.newSAXParser().getXMLReader();
        Handler h = new Handler(xmlIdToTvgId);
        reader.setContentHandler(h);
        reader.parse(new InputSource(in));
        h.data.finish();
        return h.data;
    }

    private static final class Handler extends DefaultHandler {
        final GuideModel.GuideData data = new GuideModel.GuideData();
        private final Map<String, String> bridge;

        private String channel;      // Sandy tvg-id for the open programme
        private String startRaw, stopRaw;
        private StringBuilder text;  // current element's character buffer
        private String inElement = "";
        private String title = "", desc = "", category = "";

        Handler(Map<String, String> bridge) { this.bridge = bridge; }

        @Override
        public void startElement(String uri, String localName, String qName,
                                 Attributes atts) {
            String name = (qName == null || qName.isEmpty()) ? localName : qName;
            if ("programme".equals(name)) {
                String xmlId = atts.getValue("channel");
                channel = xmlId == null ? null : bridge.get(xmlId);
                startRaw = atts.getValue("start");
                stopRaw = atts.getValue("stop");
                title = ""; desc = ""; category = "";
            } else if ("title".equals(name) || "desc".equals(name)
                    || "category".equals(name)) {
                inElement = name;
                text = new StringBuilder();
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (text != null) text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            String name = (qName == null || qName.isEmpty()) ? localName : qName;
            if (name.equals(inElement) && text != null) {
                String v = text.toString().trim();
                if ("title".equals(name)) title = v;
                else if ("desc".equals(name)) {
                    desc = v.length() > MAX_DESC ? v.substring(0, MAX_DESC) : v;
                } else if ("category".equals(name) && category.isEmpty()) {
                    category = v;
                }
                inElement = "";
                text = null;
            } else if ("programme".equals(name)) {
                if (channel != null) {
                    long st = GuideModel.parseXmltvTime(startRaw);
                    long sp = GuideModel.parseXmltvTime(stopRaw);
                    if (st >= 0 && sp > st) {
                        data.add(new GuideModel.Programme(
                                channel, st, sp, title, desc, category));
                    }
                }
                channel = null;
            }
        }
    }
}
