/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.blockchain

import akka.actor.ActorRef
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Script, ScriptWitness, Transaction}
import fr.acinq.eclair.channel.BitcoinEvent
import fr.acinq.eclair.wire.ChannelAnnouncement
import scodec.bits.ByteVector

import scala.util.{Failure, Success, Try}

/**
  * Created by PM on 19/01/2016.
  */

// @formatter:off

sealed trait Watch {
  def channel: ActorRef
  def event: BitcoinEvent
}
// we need a public key script to use electrum apis, rescanHeight is the earliest block height at which we need to scan to index the watched transaction
final case class WatchConfirmed(channel: ActorRef, txId: ByteVector32, publicKeyScript: ByteVector, minDepth: Long, event: BitcoinEvent, rescanFrom: RescanFrom) extends Watch
object WatchConfirmed {
  // if we have the entire transaction, we can get the redeemScript from the witness, and re-compute the publicKeyScript
  // we support both p2pkh and p2wpkh scripts
  def apply(channel: ActorRef, tx: Transaction, minDepth: Long, event: BitcoinEvent, rescanFrom: RescanFrom): WatchConfirmed = WatchConfirmed(channel, tx.txid, tx.txOut.map(_.publicKeyScript).headOption.getOrElse(ByteVector.empty), minDepth, event, rescanFrom)

  def extractPublicKeyScript(witness: ScriptWitness): ByteVector = Try(PublicKey(witness.stack.last)) match {
    case Success(pubKey) =>
      // if last element of the witness is a public key, then this is a p2wpkh
      Script.write(Script.pay2wpkh(pubKey))
    case Failure(_) =>
      // otherwise this is a p2wsh
      Script.write(Script.pay2wsh(witness.stack.last))
  }
}

final case class WatchSpent(channel: ActorRef, txId: ByteVector32, outputIndex: Int, publicKeyScript: ByteVector, event: BitcoinEvent, rescanFrom: RescanFrom) extends Watch
object WatchSpent {
  // if we have the entire transaction, we can get the publicKeyScript from the relevant output
  def apply(channel: ActorRef, tx: Transaction, outputIndex: Int, event: BitcoinEvent, rescanFrom: RescanFrom): WatchSpent = WatchSpent(channel, tx.txid, outputIndex, tx.txOut(outputIndex).publicKeyScript, event, rescanFrom)
}
final case class WatchSpentBasic(channel: ActorRef, txId: ByteVector32, outputIndex: Int, publicKeyScript: ByteVector, event: BitcoinEvent, rescanFrom: RescanFrom) extends Watch // we use this when we don't care about the spending tx, and we also assume txid already exists
object WatchSpentBasic {
  // if we have the entire transaction, we can get the publicKeyScript from the relevant output
  def apply(channel: ActorRef, tx: Transaction, outputIndex: Int, event: BitcoinEvent, rescanFrom: RescanFrom): WatchSpentBasic = WatchSpentBasic(channel, tx.txid, outputIndex, tx.txOut(outputIndex).publicKeyScript, event, rescanFrom)
}
// TODO: notify me if confirmation number gets below minDepth?
final case class WatchLost(channel: ActorRef, txId: ByteVector32, minDepth: Long, event: BitcoinEvent) extends Watch

trait WatchEvent {
  def event: BitcoinEvent
}
final case class WatchEventConfirmed(event: BitcoinEvent, blockHeight: Int, txIndex: Int, tx: Transaction) extends WatchEvent
final case class WatchEventSpent(event: BitcoinEvent, tx: Transaction) extends WatchEvent
final case class WatchEventSpentBasic(event: BitcoinEvent) extends WatchEvent
final case class WatchEventLost(event: BitcoinEvent) extends WatchEvent

/**
  * Publish the provided tx as soon as possible depending on locktime and csv
  */
final case class PublishAsap(tx: Transaction)
final case class ValidateRequest(ann: ChannelAnnouncement)
final case class RescanFrom(rescanTimestamp: Option[Long] = None, rescanHeight: Option[Long] = None) {
  require(rescanTimestamp.isDefined || rescanHeight.isDefined)
}
case class ImportMultiItem(address: String, label: String, timestamp: Long)
case class WatchAddressItem(address: String, label: String)
sealed trait UtxoStatus
object UtxoStatus {
  case object Unspent extends UtxoStatus
  case class Spent(spendingTxConfirmed: Boolean) extends UtxoStatus
}
final case class ValidateResult(c: ChannelAnnouncement, fundingTx: Either[Throwable, (Transaction, UtxoStatus)])

final case class GetTxWithMeta(txid: ByteVector32)
final case class GetTxWithMetaResponse(txid: ByteVector32, tx_opt: Option[Transaction], lastBlockTimestamp: Long)

// @formatter:on
