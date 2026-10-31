package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ExternalLaunchSpec;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure synthetic values; no Android composer or external transmission. */
public class MessageEditorSpecTest {
    static String repeat(String value, int count) { return new String(new char[count]).replace("\0", value); }
    @Test public void emailPreservesFieldsAndEncodesOnlyRecipientLocalPart() {
        String to="Ab_1+Tag.last-Z@ExAmPle.invalid";
        String subject=" Café %2F & + # 東京 😀 ", body="line one\n\tline two &bcc=other@example.invalid";
        ExternalLaunchSpec spec=ExternalLaunchSpec.email(to,subject,body);
        assertEquals(ExternalLaunchSpec.Kind.EMAIL_COMPOSE,spec.kind);
        assertEquals("email",spec.capability); assertEquals(to,spec.recipient);
        assertEquals(subject,spec.subject); assertEquals(body,spec.body);
        assertEquals("mailto:Ab_1%2BTag.last-Z@ExAmPle.invalid",spec.uri);
        assertFalse(spec.uri.contains("?")); assertFalse(spec.uri.contains("bcc"));
        assertEquals("",ExternalLaunchSpec.email("a@b.invalid","","").body);
    }
    @Test public void emailSingleAddressGrammarRejectsListsHeadersUrisAndNormalization() {
        for(String to:new String[]{null,"","a","@example.invalid","a@","a@@example.invalid","a@localhost"," a@example.invalid","a@example.invalid ","a\n@example.invalid","a\r@example.invalid","a\t@example.invalid","a@example.invalid\n","a@example.invalid,b@example.invalid","a@example.invalid;b@example.invalid","Name <a@example.invalid>","\"a\"@example.invalid","a(comment)@example.invalid","mailto:a@example.invalid","a%2Bb@example.invalid","a?subject=x@example.invalid","a&bcc=x@example.invalid","a#x@example.invalid","a/b@example.invalid","a:b@example.invalid","a=b@example.invalid",".a@example.invalid","a.@example.invalid","a..b@example.invalid","é@example.invalid","a@é.invalid","a@.invalid","a@example.","a@example..invalid","a@-example.invalid","a@example-.invalid","a@exa_mple.invalid","a@[127.0.0.1]"})
            assertThrows("accepted address: "+to,IllegalArgumentException.class,()->ExternalLaunchSpec.email(to,"",""));
    }
    @Test public void emailAddressAndLabelLengthBoundariesAreExact() {
        String local=repeat("a",64), label=repeat("b",63);
        assertEquals(local+"@"+label+".invalid",ExternalLaunchSpec.email(local+"@"+label+".invalid","","").recipient);
        assertThrows(IllegalArgumentException.class,()->ExternalLaunchSpec.email(local+"a@example.invalid","",""));
        assertThrows(IllegalArgumentException.class,()->ExternalLaunchSpec.email("a@"+label+"b.invalid","",""));
        String max=local+"@"+label+"."+label+"."+repeat("c",61);
        assertEquals(254,max.length()); assertEquals(max,ExternalLaunchSpec.email(max,"","").recipient);
        assertThrows(IllegalArgumentException.class,()->ExternalLaunchSpec.email(max+"c","",""));
    }
    @Test public void smsSharesOnlyDialerGrammarWithoutDialerCapability() {
        for(String number:new String[]{"0","00123","+123","123456789012345","+123456789012345"}) {
            ExternalLaunchSpec spec=ExternalLaunchSpec.sms(number,"\n\tfixture");
            assertEquals(ExternalLaunchSpec.Kind.SMS_COMPOSE,spec.kind); assertEquals("sms",spec.capability);
            assertEquals("smsto:"+number,spec.uri); assertEquals(number,spec.recipient); assertNull(spec.subject);
            assertEquals("\n\tfixture",spec.body);
        }
        for(String number:new String[]{null,"","+","++1","1234567890123456"," 123","123 ","1 2","1-2","(12)","*123#","#21#","smsto:123","tel:123","1,2","1;2","1p2","1w2","%2B123","１２３","١٢٣","123\n","123\r","123\0"})
            assertThrows(IllegalArgumentException.class,()->ExternalLaunchSpec.sms(number,""));
    }
    @Test public void textBoundsUseCodePointsAndUtf8WithoutNormalization() {
        String emoji=new String(Character.toChars(0x1f600));
        String subject=repeat(emoji,256), body=repeat(emoji,1024);
        assertEquals(subject,ExternalLaunchSpec.email("a@example.invalid",subject,body).subject);
        assertEquals(body,ExternalLaunchSpec.sms("0",body).body);
        assertEquals(repeat("x",4096),ExternalLaunchSpec.sms("0",repeat("x",4096)).body);
        assertEquals(" e\u0301 ",ExternalLaunchSpec.email("a@example.invalid"," e\u0301 ","").subject);
        assertThrows(IllegalArgumentException.class,()->ExternalLaunchSpec.email("a@example.invalid",subject+"a",""));
        assertThrows(IllegalArgumentException.class,()->ExternalLaunchSpec.email("a@example.invalid",repeat("a",257),""));
        for(String oversized:new String[]{body+"a",repeat("x",4097),repeat("é",2049)}) {
            assertThrows(IllegalArgumentException.class,()->ExternalLaunchSpec.sms("0",oversized));
            assertThrows(IllegalArgumentException.class,()->ExternalLaunchSpec.email("a@example.invalid","",oversized));
        }
    }
    @Test public void onlyBodyAllowsLfTabAndAllTextRejectsUnsafeUnicode() {
        for(String bad:new String[]{null,"\0","\r","\u001f","\u007f","\u0085","\u009f","\u200b","\u200d","\u202e","\u2066","\ufeff","\u2028","\u2029","\ud800","\udc00","\ud800a",new String(Character.toChars(0xe0001))}) {
            assertThrows(IllegalArgumentException.class,()->ExternalLaunchSpec.email("a@example.invalid",bad,""));
            assertThrows(IllegalArgumentException.class,()->ExternalLaunchSpec.email("a@example.invalid","",bad));
            assertThrows(IllegalArgumentException.class,()->ExternalLaunchSpec.sms("0",bad));
        }
        for(String control:new String[]{"\n","\t"}) {
            assertThrows(IllegalArgumentException.class,()->ExternalLaunchSpec.email("a@example.invalid",control,""));
            assertEquals(control,ExternalLaunchSpec.sms("0",control).body);
        }
    }
}
