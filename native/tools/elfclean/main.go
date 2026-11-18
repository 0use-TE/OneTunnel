// SPDX-License-Identifier: GPL-3.0-or-later
// Remove build-host RPATH/RUNPATH entries without relocating ELF sections/segments.
// This edits metadata only; it never loads or executes the target library.
package main

import (
    "debug/elf"
    "encoding/binary"
    "fmt"
    "os"
    "strings"
)

func main() {
    if len(os.Args) < 2 { panic("usage: elfclean library.so") }
    // The Play-store Termux executable wrapper can prepend an extra executable argument.
    path := os.Args[len(os.Args)-1]
    if !strings.HasSuffix(path, "/libonetunnel.so") { panic(fmt.Sprintf("unexpected ELF target: %q", os.Args)) }
    f, err := elf.Open(path)
    if err != nil { panic(err) }
    if f.Class != elf.ELFCLASS64 || f.Data != elf.ELFDATA2LSB { panic("expected ELF64 little endian") }
    dynamic := f.Section(".dynamic")
    if dynamic == nil || dynamic.Entsize != 16 { panic("unexpected dynamic section") }
    offset, size := dynamic.Offset, dynamic.Size
    _ = f.Close()
    data, err := os.ReadFile(path)
    if err != nil { panic(err) }
    if offset > uint64(len(data)) || size > uint64(len(data))-offset { panic("invalid dynamic bounds") }
    entries := make([]byte, 0, size)
    removed := 0
    for at := offset; at+16 <= offset+size; at += 16 {
        tag := binary.LittleEndian.Uint64(data[at:at+8])
        if tag == uint64(elf.DT_NULL) { break }
        if tag == uint64(elf.DT_RPATH) || tag == uint64(elf.DT_RUNPATH) { removed++; continue }
        entries = append(entries, data[at:at+16]...)
    }
    if removed == 0 { fmt.Println("No build-host RPATH/RUNPATH present"); return }
    if uint64(len(entries))+16 > size { panic("missing dynamic terminator") }
    clear(data[offset:offset+size])
    copy(data[offset:offset+size], entries)
    if err := os.WriteFile(path, data, 0600); err != nil { panic(err) }
    fmt.Printf("Removed %d build-host search path entries; segment offsets/alignment unchanged\n", removed)
}
