package com.rep.book.bookrepboot.service;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.rep.book.bookrepboot.dao.*;
import com.rep.book.bookrepboot.dto.*;
import com.rep.book.bookrepboot.util.KakaoApiUtil;
import com.rep.book.bookrepboot.util.MainUtil;
import com.rep.book.bookrepboot.util.kakao.KakaoDirections;
import com.rep.book.bookrepboot.vrp.VrpResult;
import com.rep.book.bookrepboot.vrp.VrpService;
import com.rep.book.bookrepboot.vrp.VrpVehicleRoute;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.*;

@Service
@Slf4j
public class TradeService {
    @Autowired
    TradeDao tradeDao;
    @Autowired
    BookDao bookDao;
    @Autowired
    private TradeMsgDao tradeMsgDao;
    @Autowired
    private PlatformTransactionManager manager;
    @Autowired
    private TransactionDefinition definition;
    @Autowired
    private BookTradeDao bookTradeDao;
    @Autowired
    private PathDao pathDao;
    @Autowired
    private UserDao userDao;


    // 유저 이메일에 따라 교환중인 리스트를 가져오는 메서드(trade 테이블)
    public List<PageDTO> getTradeListByEmail(String email) {
        log.info("getTradeListByEmail()");
        List<TradeDTO> tradeDTOList = tradeDao.getTradeListByEmail(email);
        List<Object> tradeList = new ArrayList<>();
        for (TradeDTO tradeDTO : tradeDTOList) {
            Map<String, Object> map = new HashMap<>();
            map.put("tradeInfo", tradeDTO);
            BookDTO firstBook = bookDao.getBookByIsbn(tradeDTO.getFir_book_isbn());
            BookDTO secondBook = bookDao.getBookByIsbn(tradeDTO.getSec_book_isbn());
            map.put("firstBook", firstBook);
            map.put("secondBook", secondBook);
            tradeList.add(map);
        }
        log.info("tradeList {}", tradeList);
        return MainUtil.setPaging(tradeList, 10);
    }

    // 교환 신청 보내는 서비스 메서드
    public String saveTradeMsg(MsgDTO msgDTO, RedirectAttributes rttr) {
        log.info("saveTradMsg() - service");
        String msg = null;
        String view = null;

        try {
            tradeMsgDao.setTradeMsg(msgDTO);
            msg = "신청 메시지 전송 완료";
            view = "redirect:my-share";
            bookTradeDao.updateBookTradeStatus(msgDTO.getBook_trade_id());
            log.info("bookTrade 상태 수정 완료");
        } catch (Exception e) {
            e.printStackTrace();
            msg = "신청 메시지 전송 실패";
            view = "redirect:share-house";
        }
        rttr.addFlashAttribute("msg", msg);

        return view;
    }

    // id를 참고하여 교환 메시지 정보를 가져오는 메서드
    public MsgDTO getMsgByID(Long msgId) {
        log.info("getMsgById()");
        MsgDTO msgDTO = tradeMsgDao.getMsgById(msgId);
        log.info("msgDTO {}", msgDTO);

        return msgDTO;
    }

    // msg status 업테이트 메서드
    // 0: 메시지만 보낸 상태  1: 수락  2: 거절  3: 취소
    public boolean updateTradeMsgStatus(Long msgId, String userEmail, int status) throws Exception {
        log.info("updateTradeMsgStatus()");
        TransactionStatus transactionStatus = manager.getTransaction(definition);

        MsgDTO msgDTO = tradeMsgDao.getMsgById(msgId);
        boolean result = false;

        if (msgDTO != null && (msgDTO.getReceived_user_email().equals(userEmail) || msgDTO.getSent_user_email().equals(userEmail))) {
            try {
                Map<String, Object> map = new HashMap<>();
                map.put("msgId", msgId);
                map.put("status", status);
                result = tradeMsgDao.updateMsgStatus(map);
                log.info("update msg_status");

                if (status == 1) { // 승낙하였다면 trade 테이블에 정보 저장
                    TradeDTO tradeDTO = new TradeDTO();
                    tradeDTO.setFir_user_email(msgDTO.getSent_user_email());
                    tradeDTO.setSec_user_email(msgDTO.getReceived_user_email());
                    tradeDTO.setFir_book_isbn(msgDTO.getSent_book_isbn());
                    tradeDTO.setSec_book_isbn(msgDTO.getReceived_book_isbn());
                    tradeDTO.setTrade_status(0);
                    tradeDao.setTrade(tradeDTO);
                }
                manager.commit(transactionStatus); // 상태 변경, trade 저장 둘 다 성공하면 진행되도록 함
            } catch (Exception e) {
                e.printStackTrace();
                manager.rollback(transactionStatus); // 둘 중 하나라도 안되면 롤백
                result = false;
            }
        }
        try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(10)) {
            executorService.getExecutorService().submit(() -> {
                try {
                    packageTradesForDeliver(6);
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
            });
        }
        return result;
    }

    private void packageTradesForDeliver(int amount) throws IOException, InterruptedException {
        log.info("packageTradesForDeliver()");
        Long lastId = tradeDao.getLastId();
        if (lastId % amount != 0) {
            return;
        }
        List<TradeDTO> tradeList = tradeDao.getTradeRemainder();
        System.out.println("tradeList=" + tradeList);
        VrpService vrpService = new VrpService();

        NodeDTO start = new NodeDTO();
        start.setEmail("출발지");
        start.setX(126.711779438);
        start.setY(37.413179094);
        start.setAddress("104동 1701호");

        vrpService.addVehicle("대충 차량", start.getEmail());

        Map<String, NodeDTO> nodeMap = new HashMap<>();
        nodeMap.put(start.getEmail(), start);
        Map<String, Map<String, NodeCostDTO>> nodeCostMap = new HashMap<>();
        List<NodeDTO> nodeList = new ArrayList<>();
        nodeList.add(start);
        for (TradeDTO trade : tradeList) {
            UserDTO firstUser = userDao.getFirstUser(trade.getFir_user_email());
            NodeDTO firstNode = new NodeDTO();
            firstNode.setEmail(firstUser.getEmail());
            firstNode.setX(firstUser.getLongitude());
            firstNode.setY(firstUser.getLatitude());
            firstNode.setAddress(firstUser.getDetail());

            UserDTO secondUser = userDao.getSecondUser(trade.getSec_user_email());
            NodeDTO secondNode = new NodeDTO();
            secondNode.setEmail(secondUser.getEmail());
            secondNode.setX(secondUser.getLongitude());
            secondNode.setY(secondUser.getLatitude());
            secondNode.setAddress(secondUser.getDetail());

            String shipmentNameFir = firstNode.getEmail() + "_" + secondNode.getEmail();
            String shipmentNameSec = secondNode.getEmail() + "_" + firstNode.getEmail();
            vrpService.addShipement(shipmentNameFir, firstNode.getEmail(), secondNode.getEmail(), trade.getFir_book_isbn());
            vrpService.addShipement(shipmentNameSec, secondNode.getEmail(), firstNode.getEmail(), trade.getSec_book_isbn());

            nodeMap.put(firstNode.getEmail(), firstNode);
            nodeMap.put(secondNode.getEmail(), secondNode);

            nodeList.add(firstNode);
            nodeList.add(secondNode);
        }


        for (int i = 0; i < nodeList.size(); i++) {
            NodeDTO startNode = nodeList.get(i);
            for (int j = 0; j < nodeList.size(); j++) {
                NodeDTO endNode = nodeList.get(j);
                // 출발지와 도착지 노드 사이의 비용을 계산
                NodeCostDTO nodeCost = getNodeCost(startNode, endNode);
                // 동일한 노드 쌍에 대해서는 비용 계산을 수행하지 않고 넘어감.
                if (i == j) {
                    continue;
                }
                if (nodeCost == null) {
                    nodeCost = new NodeCostDTO();
                    nodeCost.setDistanceMeter(0l);
                    nodeCost.setDurationSecond(0l);
                }
                Long distanceMeter = nodeCost.getDistanceMeter();
                log.info("distanceMeter={}", distanceMeter);
                Long durationSecond = nodeCost.getDurationSecond();
                log.info("durationSecond={}", durationSecond);
                String startNodeId = String.valueOf(startNode.getEmail());
                String endNodeId = String.valueOf(endNode.getEmail());
                vrpService.addCost(startNodeId, endNodeId, durationSecond, distanceMeter);
                if (!nodeCostMap.containsKey(startNodeId)) {
                    nodeCostMap.put(startNodeId, new HashMap<>());
                }
                nodeCostMap.get(startNodeId).put(endNodeId, nodeCost);
            }
        }
        List<NodeDTO> vrpNodeList = new ArrayList<>();
        VrpResult vrpResult = vrpService.getVrpResult();
        for (VrpVehicleRoute vrpVehicleRoute : vrpResult.getVrpVehicleRouteList()) {
            if ("deliverShipment".equals(vrpVehicleRoute.getActivityName())) {
                String locationId = vrpVehicleRoute.getLocationId();
                vrpNodeList.add(nodeMap.get(locationId));
            }
            System.out.println(vrpVehicleRoute);
        }
        List<String> sequenceList = new ArrayList<>();
        for (NodeDTO nodeDTO : vrpNodeList){
            sequenceList.add(nodeDTO.getEmail());
        }
        log.info(sequenceList.toString());
        String sequenceJson = new Gson().toJson(sequenceList);
        int totalDistance = 0;
        int totalDuration = 0;
        List<KakaoApiUtil.Point> totalPathPointList = new ArrayList<>();
        for (int i = 1; i < vrpNodeList.size(); i++) {
            NodeDTO prev = vrpNodeList.get(i - 1);
            NodeDTO next = vrpNodeList.get(i);
            NodeCostDTO nodeCost = nodeCostMap.get(String.valueOf(prev.getEmail())).get(String.valueOf(next.getEmail()));
            if (nodeCost == null) {
                continue;
            }
            totalDistance += nodeCost.getDistanceMeter();
            totalDuration += nodeCost.getDurationSecond();
            String pathJson = nodeCost.getPathJson();

            if (pathJson != null) {
                totalPathPointList.addAll(new ObjectMapper().readValue(pathJson, new TypeReference<List<KakaoApiUtil.Point>>() {
                }));
            }
        }
        String totalJson = new Gson().toJson(totalPathPointList);
        String tradeJson = new Gson().toJson(tradeList);
        System.out.println("totalJson=" + totalJson);
        System.out.println("tradeJson=" + tradeJson);
        if (totalJson != null && tradeJson != null) {
            PathDTO pathDTO = new PathDTO();
            pathDTO.setTrade_json(tradeJson);
            pathDTO.setPath_json(totalJson);
            pathDTO.setTotal_distance(totalDistance);
            pathDTO.setTotal_duration(totalDuration);
            pathDTO.setSequence_json(sequenceJson);
            log.info(pathDTO.toString());
            pathDao.insertPath(pathDTO);
        }
        for (TradeDTO tradeDTO : tradeList) {
            tradeDTO.setTrade_status(1);
            tradeDao.updateTrade(tradeDTO);
        }
    }

    private NodeCostDTO getNodeCost(NodeDTO prev, NodeDTO next) throws IOException, InterruptedException {
        NodeCostDTO nodeCostDTO = new NodeCostDTO();
        nodeCostDTO.setStartNodeId(prev.getEmail());
        nodeCostDTO.setEndNodeId(next.getEmail());
        NodeCostDTO nodeCost = tradeDao.getOneByParam(nodeCostDTO);
        if (nodeCost == null) {
            KakaoDirections kakaoDirections = KakaoApiUtil.getKakaoDirections(new KakaoApiUtil.Point(prev.getX(), prev.getY()),
                    new KakaoApiUtil.Point(next.getX(), next.getY()));
            List<KakaoDirections.Route> routes = kakaoDirections.getRoutes();
            KakaoDirections.Route route = routes.get(0);
            List<KakaoApiUtil.Point> pathPointList = new ArrayList<KakaoApiUtil.Point>();
            List<KakaoDirections.Route.Section> sections = route.getSections();
            if (sections == null) {
                pathPointList.add(new KakaoApiUtil.Point(prev.getX(), prev.getY()));
                pathPointList.add(new KakaoApiUtil.Point(next.getX(), next.getY()));
                nodeCost = new NodeCostDTO();
                nodeCost.setStartNodeId(prev.getEmail());
                nodeCost.setEndNodeId(next.getEmail());
                nodeCost.setDistanceMeter(0l);
                nodeCost.setDurationSecond(0l);
                nodeCost.setTollFare(0);
                nodeCost.setTaxiFare(0);
                nodeCost.setPathJson(new ObjectMapper().writeValueAsString(pathPointList));
                nodeCost.setRegDt(new Date());
                nodeCost.setModDt(new Date());
                tradeDao.add(nodeCost);
                return null;
            }

            List<KakaoDirections.Route.Section.Road> roads = sections.get(0).getRoads();
            for (KakaoDirections.Route.Section.Road road : roads) {
                List<Double> vertexes = road.getVertexes();
                for (int q = 0; q < vertexes.size(); q++) {
                    pathPointList.add(new KakaoApiUtil.Point(vertexes.get(q), vertexes.get(++q)));
                }
            }
            KakaoDirections.Route.Summary summary = route.getSummary();
            Integer distance = summary.getDistance();
            Integer duration = summary.getDuration();
            KakaoDirections.Route.Summary.Fare fare = summary.getFare();
            Integer taxi = fare.getTaxi();
            Integer toll = fare.getToll();

            nodeCost = new NodeCostDTO();
            nodeCost.setStartNodeId(prev.getEmail());
            nodeCost.setEndNodeId(next.getEmail());
            nodeCost.setDistanceMeter(distance.longValue());
            nodeCost.setDurationSecond(duration.longValue());
            nodeCost.setTollFare(toll);
            nodeCost.setTaxiFare(taxi);
            nodeCost.setPathJson(new ObjectMapper().writeValueAsString(pathPointList));
            nodeCost.setRegDt(new Date());
            nodeCost.setModDt(new Date());
            tradeDao.add(nodeCost);
        }
        return nodeCost;
    }

    // 책 정보를 조합하여 리턴하는 메서드(리스트)
    public List<Object> addBookInfoList(List<MsgDTO> msglist, int checkNum) {
        log.info("addBookInfoList()");
        List<Object> mList = new ArrayList<>();

        if (checkNum == 1) {
            try {
                for (MsgDTO msgDTO : msglist) {
                    Map<String, Object> map = new HashMap<>();
                    BookDTO book = bookDao.getBookByIsbn(msgDTO.getSent_book_isbn());
                    map.put("msgId", msgDTO.getMsg_id());
                    map.put("book", book);
                    mList.add(map);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (checkNum == 2) {
            try {
                for (MsgDTO msgDTO : msglist) {
                    Map<String, Object> map = new HashMap<>();
                    BookDTO book = bookDao.getBookByIsbn(msgDTO.getReceived_book_isbn());
                    map.put("msgId", msgDTO.getMsg_id());
                    map.put("book", book);
                    mList.add(map);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            mList = null;
        }
        return mList;
    }

    // 책 정보를 조합하여 리턴하는 메서드(dto)
    public Map<String, Object> addBookInfo(MsgDTO msgDTO) {
        log.info("addBookInfo");
        Map<String, Object> resultMap = new HashMap<>();

        try {
            BookDTO sentBook = bookDao.getBookByIsbn(msgDTO.getSent_book_isbn());
            BookDTO receivedBook = bookDao.getBookByIsbn(msgDTO.getReceived_book_isbn());
            resultMap.put("msgDTO", msgDTO);
            resultMap.put("sentBook", sentBook);
            resultMap.put("receivedBook", receivedBook);

        } catch (Exception e) {
            e.printStackTrace();
        }

        return resultMap;
    }
}
