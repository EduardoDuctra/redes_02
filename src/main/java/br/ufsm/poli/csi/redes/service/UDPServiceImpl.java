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

                    // ignora mensagens enviadas por mim mesmo
                    if (usuario != null && msg.getUsuario().equals(usuario.getNome())) {
                        continue;
                    }

                    switch (msg.getTipoMensagem()) {
                        case sonda -> {
                            Usuario u = usuariosConectados.get(msg.getUsuario());

                            // cria novo usuário se não existir
                            if (u == null) {
                                Usuario.StatusUsuario status = (msg.getStatus() != null) ?
                                        Usuario.StatusUsuario.valueOf(msg.getStatus()) :
                                        Usuario.StatusUsuario.DISPONIVEL;

                                u = new Usuario(msg.getUsuario(), status, pacote.getAddress());
                                usuariosConectados.put(u.getNome(), u);

                                // notifica listeners
                                for (UDPServiceUsuarioListener l : usuarioListeners) {
                                    l.usuarioAdicionado(u);
                                }
                            } else if (msg.getStatus() != null) {
                                u.setStatus(Usuario.StatusUsuario.valueOf(msg.getStatus()));
                                for (UDPServiceUsuarioListener l : usuarioListeners) {
                                    l.usuarioAlterado(u);
                                }
                            }

                            System.out.println("Sonda recebida de: " + msg.getUsuario() + " - status: " + msg.getStatus());
                        }

                        case msg_individual, msg_grupo -> {
                            Usuario remetente = usuariosConectados.get(msg.getUsuario());

                            if (remetente == null) {
                                // cria usuário temporário se não existir
                                remetente = new Usuario(msg.getUsuario(),
                                        Usuario.StatusUsuario.DISPONIVEL,
                                        pacote.getAddress());
                                usuariosConectados.put(remetente.getNome(), remetente);
                                for (UDPServiceUsuarioListener l : usuarioListeners) {
                                    l.usuarioAdicionado(remetente);
                                }
                            }

                            boolean chatGeral = msg.getTipoMensagem() == Mensagem.TipoMensagem.msg_grupo;

                            // evita duplicar a própria mensagem de grupo
                            if (chatGeral && usuario != null && msg.getUsuario().equals(usuario.getNome())) {
                                continue;
                            }

                            for (UDPServiceMensagemListener l : mensagemListeners) {
                                l.mensagemRecebida(msg.getMsg(), remetente, chatGeral);
                            }
                        }

                        default -> {
                            // ignorar outros tipos de mensagem
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }


    @Override
    public void enviarMensagem(String mensagem, Usuario destinatario, boolean chatGeral) {
        if (usuario == null) return;

        try {
            ObjectMapper mapper = new ObjectMapper();
            Mensagem msg = new Mensagem();

            msg.setTipoMensagem(chatGeral ? Mensagem.TipoMensagem.msg_grupo : Mensagem.TipoMensagem.msg_individual);
            msg.setUsuario(usuario.getNome());
            msg.setStatus(usuario.getStatus().toString());
            msg.setMsg(mensagem);

            byte[] bMensagem = mapper.writeValueAsBytes(msg);
            DatagramPacket pacote;

            if (chatGeral) {
                // broadcast para todos
                DatagramSocket socket = new DatagramSocket();
                socket.setBroadcast(true);
                pacote = new DatagramPacket(bMensagem, bMensagem.length, InetAddress.getByName("255.255.255.255"), 8080);
                socket.send(pacote);
                socket.close();
            } else {
                // mensagem individual
                Usuario u = usuariosConectados.get(destinatario.getNome());
                if (u != null && u.getEndereco() != null) {
                    DatagramSocket socket = new DatagramSocket();
                    pacote = new DatagramPacket(bMensagem, bMensagem.length, u.getEndereco(), 8080);
                    socket.send(pacote);
                    socket.close();
                } else {
                    System.out.println("Não foi possível enviar: endereço do destinatário desconhecido");
                }
            }

            // notifica o listener local para mostrar a mensagem na interface
            for (UDPServiceMensagemListener l : mensagemListeners) {
                l.mensagemRecebida(mensagem, usuario, chatGeral);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
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
