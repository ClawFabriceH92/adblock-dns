package io.github.clawfabriceh92.adblockdns.core

import io.github.clawfabriceh92.adblockdns.core.dns.DnsMessages
import io.github.clawfabriceh92.adblockdns.core.engine.BlockedQuery
import io.github.clawfabriceh92.adblockdns.core.engine.DnsPacketProcessor
import io.github.clawfabriceh92.adblockdns.core.filter.FilterEngine
import io.github.clawfabriceh92.adblockdns.core.filter.ListCategory
import io.github.clawfabriceh92.adblockdns.core.filter.LoadedList
import io.github.clawfabriceh92.adblockdns.core.filter.RuleMatcher
import io.github.clawfabriceh92.adblockdns.core.lists.BlocklistCompiler
import io.github.clawfabriceh92.adblockdns.core.packet.UdpPacket
import io.github.clawfabriceh92.adblockdns.core.upstream.DnsUpstream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Banc d'essai de bout en bout lancé par tools/test_tun_linux.py : l'entrée et la sortie
 * standard sont le descripteur d'une vraie interface TUN Linux, exactement comme le
 * descripteur fourni par VpnService.establish() sur Android. Chaque read() rend un paquet IP,
 * chaque write() en injecte un.
 *
 * Arguments : <fichier liste> <règles blanches séparées par des virgules> <règles noires>.
 * Le résolveur amont est simulé : il répond 203.0.113.53 à tout, sauf aux noms commençant par
 * « cloaked. » qui reçoivent un CNAME vers un domaine de la liste (CNAME cloaking).
 */
fun main(args: Array<String>) {
    val list = File(args[0]).inputStream().use { BlocklistCompiler.compile(it) }
    val allow = args.getOrNull(1).orEmpty().split(',').filter { it.isNotBlank() }
    val block = args.getOrNull(2).orEmpty().split(',').filter { it.isNotBlank() }
    val cloakTarget = args.getOrNull(3) ?: "doubleclick.net"
    val engine = FilterEngine(
        RuleMatcher(allow),
        RuleMatcher(block),
        listOf(LoadedList("liste", ListCategory.ADS_TRACKING, list.domains)),
    )
    val upstream = object : DnsUpstream {
        override fun resolve(query: ByteArray): ByteArray {
            val name = DnsMessages.parseQuestion(query)?.name.orEmpty()
            val answers = if (name.startsWith("cloaked.")) {
                listOf(TestDns.cname("x.$cloakTarget"), TestDns.a(203, 0, 113, 53))
            } else {
                listOf(TestDns.a(203, 0, 113, 53))
            }
            return TestDns.response(query, answers)
        }
    }
    var blockedCount = 0
    val processor = DnsPacketProcessor(
        engine = { engine },
        upstream = { upstream },
        listener = object : DnsPacketProcessor.Listener {
            override fun onBlocked(query: BlockedQuery, packet: UdpPacket) {
                blockedCount++
            }
        },
    )
    val input = FileInputStream(FileDescriptor.`in`)
    val output = FileOutputStream(FileDescriptor.out)
    val buffer = ByteArray(32_767)
    val trace = System.getenv("TUN_TRACE") == "1"
    System.err.println("PRET ${list.domains.size} domaines")
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        if (n == 0) continue
        val reply = processor.process(buffer, n)
        if (trace) System.err.println("lu $n octets (version IP ${buffer[0].toInt() ushr 4 and 0xF}), réponse ${reply?.size ?: 0} octets")
        if (reply != null) output.write(reply)
    }
    System.err.println("FIN $blockedCount blocages")
}
