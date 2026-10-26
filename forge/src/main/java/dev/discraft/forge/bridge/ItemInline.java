package dev.discraft.forge.bridge;

/** UTF-16 text range bound by the server to its immutable safe native-item payload. */
public record ItemInline(int start,int end,NativeItemData item){
    public ItemInline{if(start<0||end<=start||item==null)throw new IllegalArgumentException("Invalid inline item");}
}
