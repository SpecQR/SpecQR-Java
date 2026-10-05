package io.specqr;
import java.io.*;
import java.util.*;
public class CorpusAudit {
 static Object serialize(Object x) throws Exception {
  if(x==null||x instanceof String||x instanceof Boolean||x instanceof Number)return x;
  if(x instanceof Iterable<?> xs){var out=new ArrayList<Object>();for(var v:xs)out.add(serialize(v));return out;}
  String fields=switch(x.getClass().getSimpleName()){
   case "Element" -> "ai value";
   case "AiLength" -> "type exact min max";
   case "AiInfo" -> "ai label length valueKind checkDigitRule digitalLinkRole separator digitalLinkPathForPrimary";
   case "ElementStringParseResult" -> "elements hasSeparators";
   case "ValidationIssue" -> "code message reason ai value key offset elementIndex expected count";
   case "ValidationResult" -> "ok elements hasSeparators errors warnings";
   case "UnknownQuery" -> "key value";
   case "DigitalLinkParseResult" -> "elements primary pathElements queryElements unknownQuery";
   case "DigitalLinkValidationResult" -> "ok result errors warnings";
   default -> throw new IllegalStateException("Unsupported output class "+x.getClass());
  };
  Map<String,Object> out=new LinkedHashMap<>();for(String f:fields.split(" "))out.put(f,serialize(x.getClass().getMethod(f).invoke(x)));return out;
 }
 @SuppressWarnings("unchecked")
 static Object call(Map<String,Object> r){
  Map<String,Object> o=(Map<String,Object>)r.getOrDefault("options",Map.of());String s=(String)r.get("input");List<?> e=(List<?>)r.get("elements");
  var vo=new Gs1.ValidationOptions((String)o.getOrDefault("context","element-string"),(boolean)o.getOrDefault("collectAllErrors",true),(boolean)o.getOrDefault("allowUnsupportedAi",false));
  var lo=new Gs1.DigitalLinkOptions((String)o.get("baseUrl"),(String)o.get("primaryAi"),(List<String>)o.get("pathAis"),(String)o.getOrDefault("unknownQuery","preserve"),(boolean)o.getOrDefault("normalize",false),(String)o.getOrDefault("mode","specqr-deterministic"));
  return switch((String)r.get("op")){
   case "dictionary" -> Gs1.getSupportedAis();case "info" -> Gs1.getAiInfo(s);
   case "checkDigit" -> Gs1.calculateCheckDigit(s);case "validateCheckDigit" -> Gs1.validateCheckDigit(s);
   case "gtinDigit" -> Gs1.calculateGtinCheckDigit(s);case "gtinAppend" -> Gs1.appendGtinCheckDigit(s);case "gtinValidate" -> Gs1.validateGtinCheckDigit(s);
   case "ssccDigit" -> Gs1.calculateSsccCheckDigit(s);case "ssccAppend" -> Gs1.appendSsccCheckDigit(s);case "ssccValidate" -> Gs1.validateSsccCheckDigit(s);
   case "human" -> Gs1.parseHumanReadable(s);case "raw" -> Gs1.parseElementString(s);case "create" -> Gs1.createElementString(e);
   case "validateElements" -> Gs1.validateElements(e,vo);case "validateRaw" -> Gs1.validateElementString(s,vo);
   case "linkCreate" -> Gs1.createDigitalLink(e,lo);case "linkParse" -> Gs1.parseDigitalLink(s,lo);case "linkValidate" -> Gs1.validateDigitalLink(s,lo);case "linkNormalize" -> Gs1.normalizeDigitalLink(s,lo);
   default -> throw new IllegalArgumentException("unknown operation");
  };
 }
 @SuppressWarnings("unchecked")
 public static void main(String[] args)throws Exception{
  var in=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8));String line;
  while((line=in.readLine())!=null){Object out;try{out=serialize(call((Map<String,Object>)Json.parse(line)));}catch(SpecQrException ex){out=Map.of("throws",Map.of("code",ex.code(),"message",ex.getMessage()));}System.out.println(Json.write(out));}
 }
}
