/**
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.whispersystems.textsecure.api;

import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.textsecure.api.crypto.AttachmentCipherInputStream;
import org.whispersystems.textsecure.api.messages.SignalServiceAttachment.ProgressListener;
import org.whispersystems.textsecure.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.textsecure.api.messages.SignalServiceDataMessage;
import org.whispersystems.textsecure.api.messages.SignalServiceEnvelope;
import org.whispersystems.textsecure.api.push.TrustStore;
import org.whispersystems.textsecure.api.util.CredentialsProvider;
import org.whispersystems.textsecure.internal.push.PushServiceSocket;
import org.whispersystems.textsecure.internal.push.SignalServiceEnvelopeEntity;
import org.whispersystems.textsecure.internal.util.StaticCredentialsProvider;
import org.whispersystems.textsecure.internal.websocket.WebSocketConnection;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;

/**
 * The primary interface for receiving Signal Service messages.
 *
 * @author Moxie Marlinspike
 */
public class SignalServiceMessageReceiver {

  private final PushServiceSocket   socket;
  private final TrustStore          trustStore;
  private final String              url;
  private final CredentialsProvider credentialsProvider;
  private final String              userAgent;

  /**
   * Construct a SignalServiceMessageReceiver.
   *
   * @param url The URL of the Signal Service.
   * @param trustStore The {@link org.whispersystems.textsecure.api.push.TrustStore} containing
   *                   the server's TLS signing certificate.
   * @param user The Signal Service username (eg. phone number).
   * @param password The Signal Service user password.
   * @param signalingKey The 52 byte signaling key assigned to this user at registration.
   */
  public SignalServiceMessageReceiver(String url, TrustStore trustStore,
                                      String user, String password,
                                      String signalingKey, String userAgent)
  {
    this(url, trustStore, new StaticCredentialsProvider(user, password, signalingKey), userAgent);
  }

  /**
   * Construct a SignalServiceMessageReceiver.
   *
   * @param url The URL of the Signal Service.
   * @param trustStore The {@link org.whispersystems.textsecure.api.push.TrustStore} containing
   *                   the server's TLS signing certificate.
   * @param credentials The Signal Service user's credentials.
   */
  public SignalServiceMessageReceiver(String url, TrustStore trustStore,
                                      CredentialsProvider credentials, String userAgent)
  {
    this.url                 = url;
    this.trustStore          = trustStore;
    this.credentialsProvider = credentials;
    this.socket              = new PushServiceSocket(url, trustStore, credentials, userAgent);
    this.userAgent           = userAgent;
  }

  /**
   * Retrieves a SignalServiceAttachment.
   *
   * @param pointer The {@link SignalServiceAttachmentPointer}
   *                received in a {@link SignalServiceDataMessage}.
   * @param destination The download destination for this attachment.
   *
   * @return An InputStream that streams the plaintext attachment contents.
   * @throws IOException
   * @throws InvalidMessageException
   */
  public InputStream retrieveAttachment(SignalServiceAttachmentPointer pointer, File destination)
      throws IOException, InvalidMessageException
  {
    return retrieveAttachment(pointer, destination, null);
  }


  /**
   * Retrieves a SignalServiceAttachment.
   *
   * @param pointer The {@link SignalServiceAttachmentPointer}
   *                received in a {@link SignalServiceDataMessage}.
   * @param destination The download destination for this attachment.
   * @param listener An optional listener (may be null) to receive callbacks on download progress.
   *
   * @return An InputStream that streams the plaintext attachment contents.
   * @throws IOException
   * @throws InvalidMessageException
   */
  public InputStream retrieveAttachment(SignalServiceAttachmentPointer pointer, File destination, ProgressListener listener)
      throws IOException, InvalidMessageException
  {
    socket.retrieveAttachment(pointer.getRelay().orNull(), pointer.getId(), destination, listener);
    return new AttachmentCipherInputStream(destination, pointer.getKey());
  }

  /**
   * Creates a pipe for receiving SignalService messages.
   *
   * Callers must call {@link SignalServiceMessagePipe#shutdown()} when finished with the pipe.
   *
   * @return A SignalServiceMessagePipe for receiving Signal Service messages.
   */
  public SignalServiceMessagePipe createMessagePipe() {
    WebSocketConnection webSocket = new WebSocketConnection(url, trustStore, credentialsProvider, userAgent);
    return new SignalServiceMessagePipe(webSocket, credentialsProvider);
  }

  public List<SignalServiceEnvelope> retrieveMessages() throws IOException {
    return retrieveMessages(new NullMessageReceivedCallback());
  }

  public List<SignalServiceEnvelope> retrieveMessages(MessageReceivedCallback callback)
      throws IOException
  {
    List<SignalServiceEnvelope>       results  = new LinkedList<>();
    List<SignalServiceEnvelopeEntity> entities = socket.getMessages();

    for (SignalServiceEnvelopeEntity entity : entities) {
      SignalServiceEnvelope envelope =  new SignalServiceEnvelope(entity.getType(), entity.getSource(),
                                                                  entity.getSourceDevice(), entity.getRelay(),
                                                                  entity.getTimestamp(), entity.getMessage(),
                                                                  entity.getContent());

      callback.onMessage(envelope);
      results.add(envelope);

      socket.acknowledgeMessage(entity.getSource(), entity.getTimestamp());
    }

    return results;
  }


  public interface MessageReceivedCallback {
    public void onMessage(SignalServiceEnvelope envelope);
  }

  public static class NullMessageReceivedCallback implements MessageReceivedCallback {
    @Override
    public void onMessage(SignalServiceEnvelope envelope) {}
  }

}