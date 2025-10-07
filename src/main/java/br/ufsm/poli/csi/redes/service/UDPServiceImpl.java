package br.ufsm.poli.csi.redes.service;

import br.ufsm.poli.csi.redes.model.Mensagem;
import br.ufsm.poli.csi.redes.model.Usuario;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class UDPServiceImpl implements UDPService {

    private Usuario usuario;

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

                while (true) {
                    DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);
                    socket.receive(pacote);

                    String dados = new String(pacote.getData(), 0, pacote.getLength());
                    ObjectMapper mapper = new ObjectMapper();
                    Mensagem msg = mapper.readValue(dados, Mensagem.class);

                    // ignora a própria sonda
                    if (msg.getUsuario().equals(usuario.getNome()) &&
                            msg.getTipoMensagem() == Mensagem.TipoMensagem.sonda) {
                        continue;
                    }

                    // aqui você atualizaria a lista de usuários
                    // por exemplo, avisando listeners que um novo usuário foi adicionado
                    System.out.println("Sonda recebida de: " + msg.getUsuario() + " - status: " + msg.getStatus());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }


    @Override
    public void enviarMensagem(String mensagem, Usuario destinatario, boolean chatGeral) {
        // implementar envio de mensagem individual ou grupo
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
        // implementar registro do listener
    }

    @Override
    public void addListenerUsuario(UDPServiceUsuarioListener listener) {
        // implementar registro do listener
    }
}
