package br.ufsm.poli.csi.redes.service;

import br.ufsm.poli.csi.redes.model.Mensagem;
import br.ufsm.poli.csi.redes.model.Usuario;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class UDPServiceImpl implements UDPService {

    private Usuario usuario;
    private Map<String, Usuario> usuariosConectados = new HashMap<>();
    private final CopyOnWriteArrayList<UDPServiceUsuarioListener> usuarioListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<UDPServiceMensagemListener> mensagemListeners = new CopyOnWriteArrayList<>();

    public UDPServiceImpl() {
        // construtor vazio para manter a interface
    }

    private class EnviaSonda implements Runnable {

        @SneakyThrows
        @Override
        public void run() {
            ObjectMapper mapper = new ObjectMapper();
            DatagramSocket socket = new DatagramSocket();
            socket.setBroadcast(true); // habilita broadcast

            while (true) {
                Thread.sleep(5000);

                if (usuario == null) continue;

                // cria a mensagem de sonda
                Mensagem mensagem = new Mensagem();
                mensagem.setTipoMensagem(Mensagem.TipoMensagem.sonda);
                mensagem.setUsuario(usuario.getNome());
                mensagem.setStatus(usuario.getStatus().toString());

                String strMensagem = mapper.writeValueAsString(mensagem);
                byte[] bMensagem = strMensagem.getBytes();

                // envia broadcast para toda a rede
                DatagramPacket pacote = new DatagramPacket(
                        bMensagem,
                        bMensagem.length,
                        InetAddress.getByName("255.255.255.255"),
                        8080
                );

                socket.send(pacote);
            }
        }
    }

    private void receberSondas() {
        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(8080)) {
                byte[] buffer = new byte[1024];
                ObjectMapper mapper = new ObjectMapper();

                while (true) {
                    DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);
                    socket.receive(pacote);

                    String dados = new String(pacote.getData(), 0, pacote.getLength());
                    Mensagem msg = mapper.readValue(dados, Mensagem.class);

                    // ignora a própria sonda
                    if (msg.getUsuario().equals(usuario.getNome()) &&
                            msg.getTipoMensagem() == Mensagem.TipoMensagem.sonda) {
                        continue;
                    }

                    // atualiza ou adiciona usuário na lista
                    Usuario u;
                    if (!usuariosConectados.containsKey(msg.getUsuario())) {
                        u = new Usuario(msg.getUsuario(),
                                Usuario.StatusUsuario.valueOf(msg.getStatus()),
                                pacote.getAddress());
                        usuariosConectados.put(u.getNome(), u);

                        // notifica listeners
                        for (UDPServiceUsuarioListener l : usuarioListeners) {
                            l.usuarioAdicionado(u);
                        }
                    } else {
                        u = usuariosConectados.get(msg.getUsuario());
                        u.setStatus(Usuario.StatusUsuario.valueOf(msg.getStatus()));

                        // notifica listeners
                        for (UDPServiceUsuarioListener l : usuarioListeners) {
                            l.usuarioAlterado(u);
                        }
                    }

                    System.out.println("Sonda recebida de: " + u.getNome() + " - status: " + u.getStatus());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    @Override
    public void enviarMensagem(String mensagem, Usuario destinatario, boolean chatGeral) {
        new Thread(() -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                DatagramSocket socket = new DatagramSocket();
                socket.setBroadcast(true); // habilita broadcast se for grupo

                Mensagem msg = new Mensagem();
                msg.setUsuario(usuario.getNome()); // remetente
                msg.setMsg(mensagem);

                if (chatGeral) {
                    msg.setTipoMensagem(Mensagem.TipoMensagem.msg_grupo);
                    // broadcast para toda a rede
                    byte[] bMensagem = mapper.writeValueAsBytes(msg);
                    DatagramPacket pacote = new DatagramPacket(
                            bMensagem,
                            bMensagem.length,
                            InetAddress.getByName("255.255.255.255"),
                            8080
                    );
                    socket.send(pacote);
                } else {
                    // mensagem individual
                    msg.setTipoMensagem(Mensagem.TipoMensagem.msg_individual);
                    // usa endereço do destinatário da lista de usuários conectados
                    Usuario u = usuariosConectados.get(destinatario.getNome());
                    if (u != null) {
                        byte[] bMensagem = mapper.writeValueAsBytes(msg);
                        DatagramPacket pacote = new DatagramPacket(
                                bMensagem,
                                bMensagem.length,
                                u.getEndereco(),
                                8080
                        );
                        socket.send(pacote);
                    }
                }

                // notifica listeners locais de que a mensagem foi enviada
                for (UDPServiceMensagemListener l : mensagemListeners) {
                    l.mensagemRecebida(mensagem, destinatario, chatGeral);
                }

                socket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    @Override
    public void usuarioAlterado(Usuario usuario) {
        this.usuario = usuario; // atualiza usuário
        // inicia envio de sondas em background
        new Thread(new EnviaSonda()).start();
        // inicia recebimento de sondas em background
        receberSondas();
    }

    @Override
    public void addListenerMensagem(UDPServiceMensagemListener listener) {
        mensagemListeners.add(listener);
    }

    @Override
    public void addListenerUsuario(UDPServiceUsuarioListener listener) {
        usuarioListeners.add(listener);
    }
}
