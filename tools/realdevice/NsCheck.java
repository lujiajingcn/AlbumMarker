import java.io.ByteArrayInputStream;
import javax.xml.parsers.DocumentBuilderFactory;

public class NsCheck {
    public static void main(String[] args) throws Exception {
        String good = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
            + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
            + "<dc:subject><rdf:Bag><rdf:li>a</rdf:li></rdf:Bag></dc:subject>"
            + "</rdf:RDF></x:xmpmeta>";
        String bad = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
            + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
            + "<dc:subject><rdf:Bag><rdf:li>a</rdf:li></rdf:Bag></dc:subject>"
            + "</rdf:RDF></x:xmpmeta>";
        parse("good", good);
        parse("bad ", bad);
    }

    static void parse(String label, String xml) {
        try {
            var f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var d = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
            System.out.println(label + ": parsed OK, root=" + d.getDocumentElement().getNodeName());
        } catch (Exception e) {
            System.out.println(label + ": THREW " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
