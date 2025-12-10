package mosaico.acl;

public record ACLMessage (
    String illocution,
    String content,
    String sender,
    String codec
) {}
