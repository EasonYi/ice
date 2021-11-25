package com.ice.server.service.impl;

import com.alibaba.fastjson.JSON;
import com.github.pagehelper.Page;
import com.github.pagehelper.page.PageMethod;
import com.ice.common.constant.Constant;
import com.ice.common.enums.NodeTypeEnum;
import com.ice.server.dao.mapper.IceBaseMapper;
import com.ice.server.dao.mapper.IceConfMapper;
import com.ice.server.dao.mapper.IcePushHistoryMapper;
import com.ice.server.dao.model.*;
import com.ice.server.model.IceBaseSearch;
import com.ice.server.model.PageResult;
import com.ice.server.model.PushData;
import com.ice.server.model.WebResult;
import com.ice.server.service.IceBaseService;
import com.ice.server.service.IceServerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.*;

@Slf4j
@Service
public class IceBaseServiceImpl implements IceBaseService {

    @Resource
    private IceBaseMapper iceBaseMapper;

    @Resource
    private IceConfMapper iceConfMapper;

    @Resource
    private IcePushHistoryMapper pushHistoryMapper;

    @Resource
    private IceServerService serverService;

    @Resource
    private AmqpTemplate amqpTemplate;

    @Override
    public PageResult<IceBase> baseList(IceBaseSearch search) {
        Page<IceBase> page = PageMethod.startPage(search.getPageNum(), search.getPageSize());
        iceBaseMapper.selectByExample(searchToExample(search));
        PageResult<IceBase> pageResult = new PageResult<>();
        pageResult.setList(page.getResult());
        pageResult.setTotal(page.getTotal());
        pageResult.setPages(page.getPages());
        pageResult.setPageNum(page.getPageNum());
        pageResult.setPageSize(page.getPageSize());
        return pageResult;
    }

    private IceBaseExample searchToExample(IceBaseSearch search) {
        IceBaseExample example = new IceBaseExample();
        IceBaseExample.Criteria criteria = example.createCriteria();
        criteria.andStatusEqualTo((byte) 1);
        if (search.getApp() != null) {
            criteria.andAppEqualTo(search.getApp());
        }
        if (search.getBaseId() != null) {
            criteria.andIdEqualTo(search.getBaseId());
            return example;
        }
        if (!StringUtils.isEmpty(search.getName())) {
            criteria.andNameLike(search.getName() + "%");
        }
        if (!StringUtils.isEmpty(search.getScene())) {
            criteria.andScenesFindInSet(search.getScene());
        }
        return example;
    }

    @Override
    @Transactional
    public Long baseEdit(IceBase base) {
        base.setUpdateAt(new Date());
        if (base.getId() == null) {
            /*新增的需要在conf里新建一个root root默认是none*/
            if (base.getConfId() == null) {
                IceConf createConf = new IceConf();
                createConf.setApp(base.getApp());
                createConf.setType(NodeTypeEnum.NONE.getType());
                createConf.setUpdateAt(new Date());
                iceConfMapper.insertSelective(createConf);
                base.setConfId(createConf.getId());
            }
            base.setConfId(base.getConfId());
            iceBaseMapper.insertSelective(base);
            return base.getId();
        }
        iceBaseMapper.updateByPrimaryKeySelective(base);
        return base.getId();
    }

    @Override
    @Transactional
    public Long push(Integer app, Long iceId, String reason) {
        IceBase base = iceBaseMapper.selectByPrimaryKey(iceId);
        if (base == null) {
            return null;
        }
        base.setUpdateAt(new Date());
        if (base.getScenes() == null) {
            base.setScenes("");
        }
        IcePushHistory history = new IcePushHistory();
        history.setApp(base.getApp());
        history.setIceId(iceId);
        history.setReason(reason);
        history.setOperator("zjn");
        history.setPushData(getPushDataJson(base));
        pushHistoryMapper.insertSelective(history);
        return history.getId();
    }

    private String getPushDataJson(IceBase base) {
        return JSON.toJSONString(getPushData(base));
    }

    private PushData getPushData(IceBase base) {
        PushData pushData = new PushData();
        pushData.setBase(base);
        Object obj = amqpTemplate.convertSendAndReceive(Constant.getShowConfExchange(), String.valueOf(base.getApp()),
                String.valueOf(base.getId()));
        if (obj != null) {
            String json = (String) obj;
            if (!StringUtils.isEmpty(json)) {
                Map map = JSON.parseObject(json, Map.class);
                if (!CollectionUtils.isEmpty(map)) {
                    Map handlerMap = (Map) map.get("handler");
                    if (!CollectionUtils.isEmpty(handlerMap)) {
                        Map rootMap = (Map) handlerMap.get("root");
                        if (!CollectionUtils.isEmpty(rootMap)) {
                            Set<Long> allIdSet = new HashSet<>();
                            findAllConfIds(rootMap, allIdSet);
                            if (!CollectionUtils.isEmpty(allIdSet)) {
                                IceConfExample confExample = new IceConfExample();
                                confExample.createCriteria().andIdIn(new ArrayList<>(allIdSet));
                                List<IceConf> iceConfs = iceConfMapper.selectByExample(confExample);
                                if (!CollectionUtils.isEmpty(iceConfs)) {
                                    for (IceConf conf : iceConfs) {
                                        conf.setUpdateAt(new Date());
                                        if (isRelation(conf.getType()) && conf.getSonIds() == null) {
                                            conf.setSonIds("");
                                        }
                                    }
                                    pushData.setConfs(iceConfs);
                                }
                            }
                        }
                    }
                }
            }
        }
        return pushData;
    }

    @Override
    public PageResult<IcePushHistory> history(Integer app, Long iceId, Integer pageNum, Integer pageSize) {
        IcePushHistoryExample example = new IcePushHistoryExample();
        example.createCriteria().andAppEqualTo(app).andIceIdEqualTo(iceId);
        example.setOrderByClause("create_at desc");
        Page<IcePushHistory> page = PageMethod.startPage(pageNum, pageSize);
        pushHistoryMapper.selectByExample(example);
        PageResult<IcePushHistory> pageResult = new PageResult<>();
        pageResult.setList(page.getResult());
        pageResult.setTotal(page.getTotal());
        pageResult.setPages(page.getPages());
        pageResult.setPageNum(page.getPageNum());
        pageResult.setPageSize(page.getPageSize());
        return pageResult;
    }

    @Override
    public String exportData(Long iceId, Long pushId) {
        if (pushId != null && pushId > 0) {
            IcePushHistory history = pushHistoryMapper.selectByPrimaryKey(pushId);
            if (history != null) {
                return history.getPushData();
            }
            return "";
        }
        IceBaseExample baseExample = new IceBaseExample();
        baseExample.createCriteria().andIdEqualTo(iceId);
        IceBase base = iceBaseMapper.selectByPrimaryKey(iceId);
        if (base == null) {
            return "";
        }
        base.setUpdateAt(new Date());
        if (base.getScenes() == null) {
            base.setScenes("");
        }
        return getPushDataJson(base);
    }

    @Override
    public void rollback(Long pushId) {
        if (pushId != null) {
            IcePushHistoryExample historyExample = new IcePushHistoryExample();
            historyExample.createCriteria().andIdEqualTo(pushId);
            IcePushHistory history = pushHistoryMapper.selectByPrimaryKey(pushId);
            if (history != null) {
                importData(JSON.parseObject(history.getPushData(), PushData.class));
            }
        }
    }

    @Override
    @Transactional
    public void importData(PushData data) {
        IceBase base = data.getBase();
        List<IceConf> confs = data.getConfs();
        if (!CollectionUtils.isEmpty(confs)) {
            for (IceConf conf : confs) {
                IceConfExample confExample = new IceConfExample();
                confExample.createCriteria().andIdEqualTo(conf.getId());
                List<IceConf> confList = iceConfMapper.selectByExample(confExample);
                if (CollectionUtils.isEmpty(confList)) {
                    conf.setCreateAt(null);
                    conf.setUpdateAt(new Date());
                    iceConfMapper.insertSelectiveWithId(conf);
                } else {
                    conf.setId(null);
                    conf.setUpdateAt(new Date());
                    iceConfMapper.updateByExampleSelective(conf, confExample);
                }
            }
        }
        if (base != null) {
            IceBaseExample baseExample = new IceBaseExample();
            baseExample.createCriteria().andIdEqualTo(base.getId());
            List<IceBase> baseList = iceBaseMapper.selectByExample(baseExample);
            if (CollectionUtils.isEmpty(baseList)) {
                base.setCreateAt(null);
                base.setUpdateAt(new Date());
                iceBaseMapper.insertSelectiveWithId(base);
            } else {
                base.setId(null);
                base.setUpdateAt(new Date());
                iceBaseMapper.updateByExampleSelective(base, baseExample);
            }
        }
    }

    public static boolean isRelation(Byte type) {
        return type == NodeTypeEnum.NONE.getType() || type == NodeTypeEnum.ALL.getType()
                || type == NodeTypeEnum.AND.getType() || type == NodeTypeEnum.TRUE.getType()
                || type == NodeTypeEnum.ANY.getType();
    }

    private void findAllConfIds(Map map, Set<Long> ids) {
        Long nodeId = (Long) map.get("iceNodeId");
        if (nodeId != null) {
            ids.add(nodeId);
        }
        Map forward = (Map) map.get("iceForward");
        if (forward != null) {
            findAllConfIds(forward, ids);
        }
        List<Map> children = getChild(map);
        if (CollectionUtils.isEmpty(children)) {
            return;
        }
        for (Map child : children) {
            findAllConfIds(child, ids);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map> getChild(Map map) {
        return (List) map.get("children");
    }

}
